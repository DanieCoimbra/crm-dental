import "./globals.css";
import ThemeProvider from "./ThemeProvider";
import { AlertProvider } from '@/components/AlertContext'
import SWRProvider from '@/components/SWRProvider'

export const metadata = {
  title: 'Dental Clinic CRM',
  description: 'Sistema de gestão para clínicas odontológicas',
  manifest: '/manifest.json',
}

export const viewport = {
  themeColor: '#0ea5e9',
}

export default function RootLayout({ children }) {
  return (
    <html lang="pt-BR" suppressHydrationWarning>
      <body>
        <SWRProvider>
          <ThemeProvider>
            <AlertProvider>
              {children}
            </AlertProvider>
          </ThemeProvider>
        </SWRProvider>
      </body>
    </html>
  )
}
