import { useState, useRef, useEffect } from 'react'

export default function SearchableSelect({ options, value, onChange, placeholder }) {
  const [search, setSearch] = useState('')
  const [isOpen, setIsOpen] = useState(false)
  const wrapperRef = useRef(null)

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target)) {
        setIsOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const selectedOption = options.find(opt => opt.id === value)
  
  // Update search text when a selection is made or cleared
  useEffect(() => {
    if (selectedOption) {
      setSearch(selectedOption.label)
    } else {
      setSearch('')
    }
  }, [value, selectedOption])

  const normalize = (str) => (str || '').toLowerCase().replace(/[^a-z0-9]/g, '')

  const filteredOptions = options.filter(opt => 
    normalize(opt.label).includes(normalize(search))
  ).slice(0, 5)

  return (
    <div ref={wrapperRef} style={{ position: 'relative', width: '100%' }}>
      <input
        type="text"
        placeholder={placeholder}
        value={search}
        onChange={(e) => {
          setSearch(e.target.value)
          setIsOpen(true)
          if (value) onChange('') // clear selection if typing again
        }}
        onFocus={() => setIsOpen(true)}
        style={{
          width: '100%',
          padding: '0.75rem',
          borderRadius: '8px',
          border: '1px solid var(--border-color)',
          background: 'var(--bg-tertiary)',
          color: 'var(--text-primary)',
          boxSizing: 'border-box'
        }}
        required={!value} // requires selection
      />
      
      {isOpen && search.trim().length > 0 && !value && (
        <ul style={{
          position: 'absolute',
          top: '100%',
          left: 0,
          right: 0,
          maxHeight: '200px',
          overflowY: 'auto',
          background: 'var(--bg-secondary)',
          border: '1px solid var(--border-color)',
          borderRadius: '8px',
          marginTop: '4px',
          padding: 0,
          listStyle: 'none',
          zIndex: 50,
          boxShadow: '0 4px 6px rgba(0,0,0,0.1)'
        }}>
          {filteredOptions.length === 0 ? (
            <li style={{ padding: '0.75rem', color: 'var(--text-secondary)' }}>Nenhum resultado encontrado</li>
          ) : (
            filteredOptions.map(opt => (
              <li
                key={opt.id}
                onClick={() => {
                  onChange(opt.id)
                  setSearch(opt.label)
                  setIsOpen(false)
                }}
                style={{
                  padding: '0.75rem',
                  cursor: 'pointer',
                  borderBottom: '1px solid var(--border-color)',
                  color: 'var(--text-primary)',
                  backgroundColor: value === opt.id ? 'var(--bg-tertiary)' : 'transparent'
                }}
                onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'}
                onMouseLeave={(e) => e.currentTarget.style.backgroundColor = value === opt.id ? 'var(--bg-tertiary)' : 'transparent'}
              >
                {opt.label}
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  )
}
