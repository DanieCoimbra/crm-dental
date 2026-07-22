import React, { useState, useEffect, forwardRef, useImperativeHandle } from 'react'

const CommandList = forwardRef((props, ref) => {
  const [selectedIndex, setSelectedIndex] = useState(0)

  const selectItem = index => {
    const item = props.items[index]
    if (item) {
      props.command(item)
    }
  }

  const upHandler = () => {
    setSelectedIndex((selectedIndex + props.items.length - 1) % props.items.length)
  }

  const downHandler = () => {
    setSelectedIndex((selectedIndex + 1) % props.items.length)
  }

  const enterHandler = () => {
    selectItem(selectedIndex)
  }

  useEffect(() => {
    setSelectedIndex(0)
  }, [props.items])

  useImperativeHandle(ref, () => ({
    onKeyDown: ({ event }) => {
      if (event.key === 'ArrowUp') {
        upHandler()
        return true
      }
      if (event.key === 'ArrowDown') {
        downHandler()
        return true
      }
      if (event.key === 'Enter') {
        enterHandler()
        return true
      }
      return false
    },
  }))

  return (
    <div style={{
      backgroundColor: 'var(--bg-secondary)',
      border: '1px solid var(--border-color)',
      borderRadius: '8px',
      boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
      overflow: 'hidden',
      padding: '0.5rem',
      display: 'flex',
      flexDirection: 'column',
      gap: '0.25rem',
      minWidth: '200px'
    }}>
      {props.items.length ? props.items.map((item, index) => (
        <button
          key={index}
          onClick={() => selectItem(index)}
          style={{
            background: index === selectedIndex ? 'var(--bg-tertiary)' : 'transparent',
            border: 'none',
            textAlign: 'left',
            padding: '0.5rem 0.75rem',
            borderRadius: '4px',
            cursor: 'pointer',
            color: 'var(--text-primary)'
          }}
        >
          <div style={{ fontWeight: 600, fontSize: '0.9rem' }}>{item.title}</div>
          <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{item.description}</div>
        </button>
      )) : (
        <div style={{ padding: '0.5rem', color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
          Nenhum modelo encontrado.
        </div>
      )}
    </div>
  )
})

CommandList.displayName = 'CommandList'

export default CommandList
