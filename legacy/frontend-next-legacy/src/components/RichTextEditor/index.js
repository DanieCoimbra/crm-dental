import React, { useEffect, useState } from 'react'
import { useEditor, EditorContent } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { Color } from '@tiptap/extension-color'
import { TextStyle } from '@tiptap/extension-text-style'
import SlashCommands, { getSuggestionItems, renderItems } from './SlashCommands'
import { Bold, Italic, Strikethrough, Heading1, Heading2, List, ListOrdered, Undo, Redo, Palette } from 'lucide-react'

const MenuBar = ({ editor }) => {
  const [showColorPicker, setShowColorPicker] = useState(false)

  if (!editor) {
    return null
  }

  const presetColors = [
    '#000000', '#ef4444', '#f97316', '#f59e0b', '#84cc16', '#22c55e', '#10b981', 
    '#06b6d4', '#0ea5e9', '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7', 
    '#d946ef', '#ec4899', '#f43f5e', '#64748b'
  ]

  const btnStyle = (isActive) => ({
    background: isActive ? 'var(--accent)' : 'transparent',
    color: isActive ? 'white' : 'var(--text-primary)',
    border: 'none',
    padding: '0.4rem',
    borderRadius: '6px',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    transition: 'all 0.2s',
  })

  return (
    <div style={{
      display: 'flex',
      flexWrap: 'wrap',
      gap: '0.5rem',
      padding: '0.5rem',
      borderBottom: '1px solid var(--border-color)',
      backgroundColor: 'var(--bg-secondary)',
      borderTopLeftRadius: '8px',
      borderTopRightRadius: '8px',
      alignItems: 'center'
    }}>
      <button type="button" onClick={() => editor.chain().focus().toggleBold().run()} style={btnStyle(editor.isActive('bold'))} title="Negrito">
        <Bold size={16} />
      </button>
      <button type="button" onClick={() => editor.chain().focus().toggleItalic().run()} style={btnStyle(editor.isActive('italic'))} title="Itálico">
        <Italic size={16} />
      </button>
      <button type="button" onClick={() => editor.chain().focus().toggleStrike().run()} style={btnStyle(editor.isActive('strike'))} title="Riscado">
        <Strikethrough size={16} />
      </button>
      
      <div style={{ width: '1px', height: '24px', backgroundColor: 'var(--border-color)', margin: '0 0.25rem' }} />
      
      <button type="button" onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()} style={btnStyle(editor.isActive('heading', { level: 1 }))} title="Título 1">
        <Heading1 size={16} />
      </button>
      <button type="button" onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} style={btnStyle(editor.isActive('heading', { level: 2 }))} title="Título 2">
        <Heading2 size={16} />
      </button>
      
      <div style={{ width: '1px', height: '24px', backgroundColor: 'var(--border-color)', margin: '0 0.25rem' }} />

      <button type="button" onClick={() => editor.chain().focus().toggleBulletList().run()} style={btnStyle(editor.isActive('bulletList'))} title="Lista">
        <List size={16} />
      </button>
      <button type="button" onClick={() => editor.chain().focus().toggleOrderedList().run()} style={btnStyle(editor.isActive('orderedList'))} title="Lista Numerada">
        <ListOrdered size={16} />
      </button>
      
      <div style={{ width: '1px', height: '24px', backgroundColor: 'var(--border-color)', margin: '0 0.25rem' }} />

      <div style={{ position: 'relative', display: 'flex', alignItems: 'center', gap: '0.25rem' }} title="Cor do texto">
        <button
          type="button"
          onClick={() => setShowColorPicker(!showColorPicker)}
          style={{
            ...btnStyle(false),
            display: 'flex',
            alignItems: 'center',
            gap: '0.25rem',
            background: 'transparent'
          }}
        >
          <Palette size={16} color="var(--text-secondary)" />
          <div style={{ 
            width: '16px', 
            height: '16px', 
            borderRadius: '4px', 
            backgroundColor: editor.getAttributes('textStyle').color || '#000000',
            border: '1px solid var(--border-color)'
          }} />
        </button>

        {showColorPicker && (
          <div style={{ 
            position: 'absolute', 
            top: '100%', 
            left: 0, 
            marginTop: '0.5rem', 
            backgroundColor: 'var(--bg-secondary)', 
            padding: '0.75rem', 
            borderRadius: '12px', 
            border: '1px solid var(--border-color)', 
            boxShadow: '0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1)', 
            zIndex: 100,
            display: 'grid',
            gridTemplateColumns: 'repeat(5, 1fr)',
            gap: '0.5rem',
            width: 'max-content'
          }}>
            {presetColors.map(color => (
              <button
                key={color}
                type="button"
                onClick={() => {
                  editor.chain().focus().setColor(color).run()
                  setShowColorPicker(false)
                }}
                style={{
                  width: '24px',
                  height: '24px',
                  borderRadius: '50%',
                  backgroundColor: color,
                  border: editor.getAttributes('textStyle').color === color ? '2px solid var(--text-primary)' : '1px solid var(--border-color)',
                  cursor: 'pointer',
                  transition: 'transform 0.1s',
                  transform: editor.getAttributes('textStyle').color === color ? 'scale(1.1)' : 'scale(1)'
                }}
                onMouseEnter={e => e.currentTarget.style.transform = 'scale(1.1)'}
                onMouseLeave={e => e.currentTarget.style.transform = editor.getAttributes('textStyle').color === color ? 'scale(1.1)' : 'scale(1)'}
              />
            ))}
          </div>
        )}
      </div>

      <div style={{ flex: 1 }} />

      <button type="button" onClick={() => editor.chain().focus().undo().run()} disabled={!editor.can().undo()} style={{...btnStyle(false), opacity: editor.can().undo() ? 1 : 0.5}}>
        <Undo size={16} />
      </button>
      <button type="button" onClick={() => editor.chain().focus().redo().run()} disabled={!editor.can().redo()} style={{...btnStyle(false), opacity: editor.can().redo() ? 1 : 0.5}}>
        <Redo size={16} />
      </button>
    </div>
  )
}

export default function RichTextEditor({ value, onChange, placeholder }) {
  const editor = useEditor({
    extensions: [
      StarterKit,
      TextStyle,
      Color,
      SlashCommands.configure({
        suggestion: {
          items: getSuggestionItems,
          render: renderItems,
        },
      }),
    ],
    content: value,
    onUpdate: ({ editor }) => {
      onChange(editor.getHTML())
    },
    editorProps: {
      attributes: {
        class: 'prose prose-sm sm:prose lg:prose-lg xl:prose-2xl mx-auto focus:outline-none',
        style: 'min-height: 150px; padding: 1rem; color: var(--text-primary);',
      },
      transformPastedHTML(html) {
        // Simple regex to remove base64 images to prevent db overflow
        return html.replace(/<img[^>]+src="data:image\/[^;]+;base64[^>]+>/g, '')
      }
    },
  })

  // Keep content in sync if value is reset from outside
  useEffect(() => {
    if (editor && value === '') {
      editor.commands.setContent('')
    }
  }, [value, editor])

  return (
    <div style={{ 
      border: '1px solid var(--border-color)', 
      borderRadius: '8px', 
      backgroundColor: 'var(--bg-secondary)',
      overflow: 'hidden'
    }}>
      <MenuBar editor={editor} />
      <EditorContent editor={editor} />
    </div>
  )
}
