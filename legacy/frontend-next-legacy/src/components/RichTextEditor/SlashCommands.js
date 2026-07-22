import { Extension } from '@tiptap/core'
import Suggestion from '@tiptap/suggestion'
import { ReactRenderer } from '@tiptap/react'
import tippy from 'tippy.js'
import CommandList from './CommandList'

export default Extension.create({
  name: 'slashCommands',

  addOptions() {
    return {
      suggestion: {
        char: '/',
        command: ({ editor, range, props }) => {
          props.command({ editor, range })
        },
      },
    }
  },

  addProseMirrorPlugins() {
    return [
      Suggestion({
        editor: this.editor,
        ...this.options.suggestion,
      }),
    ]
  },
})

export const getSuggestionItems = ({ query }) => {
  const items = [
    {
      title: 'Anamnese',
      description: 'Histórico e queixa principal',
      command: ({ editor, range }) => {
        editor
          .chain()
          .focus()
          .deleteRange(range)
          .insertContent('<h3>Anamnese</h3><p><strong>Queixa Principal:</strong> </p><p><strong>Histórico da Doença Atual:</strong> </p><p><strong>Alergias:</strong> Nenhuma relatada.</p>')
          .run()
      },
    },
    {
      title: 'Exame Físico',
      description: 'Sinais vitais e observações',
      command: ({ editor, range }) => {
        editor
          .chain()
          .focus()
          .deleteRange(range)
          .insertContent('<h3>Exame Físico</h3><p><strong>Pressão Arterial:</strong>  mmHg</p><p><strong>Frequência Cardíaca:</strong>  bpm</p><p><strong>Observações:</strong> </p>')
          .run()
      },
    },
    {
      title: 'Receita Simples',
      description: 'Estrutura de prescrição médica',
      command: ({ editor, range }) => {
        editor
          .chain()
          .focus()
          .deleteRange(range)
          .insertContent('<p><strong>Uso Oral:</strong></p><ol><li><strong>Medicamento X 500mg</strong><br>Tomar 1 comprimido de 8 em 8 horas por 5 dias.</li></ol>')
          .run()
      },
    },
    {
      title: 'Atestado',
      description: 'Declaração padrão',
      command: ({ editor, range }) => {
        editor
          .chain()
          .focus()
          .deleteRange(range)
          .insertContent('<p>Atesto, para os devidos fins, que o(a) paciente esteve em consulta sob meus cuidados no dia de hoje, necessitando de <strong>X dias de repouso</strong> a partir desta data.</p>')
          .run()
      },
    }
  ]

  return items.filter(item => item.title.toLowerCase().startsWith(query.toLowerCase())).slice(0, 10)
}

export const renderItems = () => {
  let component
  let popup

  return {
    onStart: props => {
      component = new ReactRenderer(CommandList, {
        props,
        editor: props.editor,
      })

      if (!props.clientRect) {
        return
      }

      popup = tippy('body', {
        getReferenceClientRect: props.clientRect,
        appendTo: () => document.body,
        content: component.element,
        showOnCreate: true,
        interactive: true,
        trigger: 'manual',
        placement: 'bottom-start',
      })
    },

    onUpdate(props) {
      component.updateProps(props)

      if (!props.clientRect) {
        return
      }

      popup[0].setProps({
        getReferenceClientRect: props.clientRect,
      })
    },

    onKeyDown(props) {
      if (props.event.key === 'Escape') {
        popup[0].hide()
        return true
      }

      return component.ref?.onKeyDown(props)
    },

    onExit() {
      popup[0].destroy()
      component.destroy()
    },
  }
}
