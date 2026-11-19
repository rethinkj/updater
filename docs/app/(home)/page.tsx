import Link from 'next/link';
import type { ReactNode } from 'react';
import { repository } from '@/lib/shared';

function Button({ href, children, primary }: { href: string; children: ReactNode; primary?: boolean }) {
  const look = primary
    ? 'bg-rj-accent text-white hover:opacity-90'
    : 'border bg-fd-card hover:bg-fd-accent';
  return (
    <Link href={href} className={`inline-flex h-10 items-center rounded-md px-4 text-sm font-medium transition-colors ${look}`}>
      {children}
    </Link>
  );
}

export default function HomePage() {
  return (
    <main className="mx-auto w-full max-w-[1120px] px-4 md:px-8">
      <section className="pt-16 pb-10 md:pt-24 text-center">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/logo.svg" alt="" width={56} height={56} className="mx-auto mb-6" />
        <h1 className="text-4xl md:text-5xl font-semibold tracking-tight">rethinkj Updater</h1>
        <p className="mx-auto mt-5 max-w-[640px] text-lg text-fd-muted-foreground leading-relaxed">
          A Java library that checks for updates of a desktop app, downloads and verifies them,
          reads their release notes, and applies them on Windows and Linux.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button href="/docs" primary>Read the docs</Button>
          <Button href={repository}>GitHub</Button>
        </div>
        <p className="mt-6 text-sm text-fd-muted-foreground">
          Early: version 0.1.0-SNAPSHOT, Java 25, Windows 11 and Linux on x64. SNAPSHOTs on Maven Central, no release yet.
        </p>
      </section>

      <section className="py-12 border-t">
        <div className="grid gap-3 sm:grid-cols-3">
          {[
            ['Get started', '/docs/get-started', 'Add the dependency to a Maven project.'],
            ['Concepts', '/docs/concepts', 'Sources, verification, release notes and applying updates.'],
            ['Reference', '/docs/reference', 'Platforms and the changelog.'],
          ].map(([title, href, text]) => (
            <Link key={href} href={href} className="rounded-xl border bg-fd-card p-4 hover:bg-fd-accent transition-colors">
              <div className="font-medium">{title}</div>
              <div className="mt-1 text-sm text-fd-muted-foreground">{text}</div>
            </Link>
          ))}
        </div>
      </section>
    </main>
  );
}
