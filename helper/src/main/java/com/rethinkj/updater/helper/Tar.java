package com.rethinkj.updater.helper;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads a tar archive in the ustar format, with long names as GNU tar and PAX write them. */
final class Tar {

  /** The end of a tar archive. A name alone does not tell it: a ustar name may be all prefix. */
  private static final byte[] END = new byte[512];

  private Tar() {}

  static void read(InputStream in, Archive.Visitor visitor) throws IOException {
    byte[] header = new byte[512];
    String longName = null; // from a GNU or PAX header, for the entry that follows it
    while (true) {
      if (in.readNBytes(header, 0, 512) < 512) {
        throw new EOFException("The tar archive ends without its end marker");
      }
      if (Arrays.equals(header, END)) {
        return;
      }
      long size = number(header, 124, 12);
      Data data = new Data(in, size);
      char type = (char) header[156];
      String name = longName != null ? longName : name(header);
      longName = null;
      switch (type) {
        case '0', '\0', '7' -> visitor.visit(name, false, (int) number(header, 100, 8), null, data);
        case '5' -> visitor.visit(name, true, -1, null, data);
        // shortcut: a target that GNU tar or PAX keeps in a header of its own, such as one over 100
        // bytes, is rejected; read it there once an app has such a link.
        case '2' -> visitor.visit(name, false, -1, string(header, 157, 100), data);
        case 'L' -> {
          byte[] bytes = data.readAllBytes();
          longName = string(bytes, 0, bytes.length);
        }
        case 'x' -> {
          byte[] records = data.readAllBytes();
          longName = paxPath(records);
        }
        case 'g' -> { // PAX values for all entries; paxPath rejects a size or sparse map in them
          String path = paxPath(data.readAllBytes());
          if (path != null) {
            throw new IOException("The tar archive gives all its files one path");
          }
        }
        default ->
          throw new IOException(name + " is a link or a special file, not a file or folder");
      }
      in.skipNBytes(data.left + (-size & 511)); // what was not read, and the rest of the block
    }
  }

  /** The name in a ustar header, with its prefix, which GNU tar uses for other things. */
  private static String name(byte[] header) {
    String name = string(header, 0, 100);
    boolean ustar = string(header, 257, 6).equals("ustar");
    String prefix = ustar ? string(header, 345, 155) : "";
    return prefix.isEmpty() ? name : prefix + "/" + name;
  }

  /** The {@code path} in PAX records, {@code "<length> <key>=<value>\n"}, or null. */
  private static String paxPath(byte[] records) throws IOException {
    String path = null;
    try {
      int i = 0;
      while (i < records.length) {
        int space = i;
        while (records[space] != ' ') {
          space++;
        }
        int length = Integer.parseInt(new String(records, i, space - i, StandardCharsets.US_ASCII));
        String entry =
            new String(records, space + 1, i + length - space - 2, StandardCharsets.UTF_8);
        if (entry.startsWith("path=")) {
          path = entry.substring(5);
        } else if (entry.startsWith("size=") || entry.startsWith("GNU.sparse.")) {
          // A file over 8 GB, which no app has, or one packed with tar --sparse.
          throw new IOException(
              "The tar archive holds a file over 8 GB or a sparse file, which the helper does not"
                  + " read");
        } else if (entry.startsWith("linkpath=")) {
          throw new IOException(
              "The tar archive holds a link whose target, such as one over 100 bytes, has a header"
                  + " of its own, which the helper does not read");
        }
        i += length;
      }
    } catch (NumberFormatException | IndexOutOfBoundsException e) { // a bad length
      throw new IOException("The tar archive has a bad PAX header", e);
    }
    return path;
  }

  /** An octal number in a tar header, ended by a space or NUL. */
  private static long number(byte[] header, int offset, int length) throws IOException {
    String digits = string(header, offset, length).trim();
    try {
      return digits.isEmpty() ? 0 : Long.parseLong(digits, 8);
    } catch (NumberFormatException e) { // also the base-256 sizes of files over 8 GB
      throw new IOException("The tar archive has a bad header", e);
    }
  }

  /** The text in a field up to its first NUL. */
  private static String string(byte[] bytes, int offset, int length) {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) {
      end++;
    }
    return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
  }

  /** The data of one tar entry: the next {@code left} bytes of the archive. */
  private static final class Data extends InputStream {
    private final InputStream in;
    private long left;

    Data(InputStream in, long size) {
      this.in = in;
      this.left = size;
    }

    @Override
    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left == 0) {
        return -1;
      }
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n < 0) {
        throw new EOFException("The tar archive ends inside an entry");
      }
      left -= n;
      return n;
    }
  }
}
