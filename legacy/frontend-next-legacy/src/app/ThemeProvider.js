'use client'

import { createContext, useContext, useEffect, useState } from 'react'

const ThemeContext = createContext({
  theme: 'light',
  toggleTheme: () => {},
})

export default function ThemeProvider({ children }) {
  const [theme, setTheme] = useState('light')
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
    const storedTheme = localStorage.getItem('crm-theme')
    if (storedTheme) {
      setTheme(storedTheme)
      document.documentElement.setAttribute('data-theme', storedTheme)
    } else if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
      setTheme('dark')
      document.documentElement.setAttribute('data-theme', 'dark')
    }
  }, [])

  const toggleTheme = () => {
    const newTheme = theme === 'light' ? 'dark' : 'light'
    setTheme(newTheme)
    localStorage.setItem('crm-theme', newTheme)
    document.documentElement.setAttribute('data-theme', newTheme)
  }

  if (!mounted) return <>{children}</>

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  )
}

export const useTheme = () => useContext(ThemeContext)
