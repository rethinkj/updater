import type { Metadata } from 'next';
import { Provider } from '@/components/provider';
import './global.css';

export const metadata: Metadata = {
  metadataBase: new URL('https://updater.rethinkj.com'),
  title: { default: 'rethinkj Updater', template: '%s · rethinkj Updater' },
  description:
    'A Java library that checks for, downloads, verifies and applies updates of desktop apps on Windows and Linux.',
  icons: { icon: '/logo.svg' },
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="flex flex-col min-h-screen">
        <Provider>{children}</Provider>
      </body>
    </html>
  );
}
