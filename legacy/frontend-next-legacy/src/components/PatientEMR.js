import { useState, useEffect } from 'react'
import axios from '@/lib/axios'
import { FileText, Clock, FilePlus, Download, Trash2, Printer } from 'lucide-react'
import RichTextEditor from './RichTextEditor'

export default function PatientEMR({ patientId, user, showAlert }) {
  const [emrTab, setEmrTab] = useState('evolucao')
  const [evolutions, setEvolutions] = useState([])
  const [prescriptions, setPrescriptions] = useState([])
  const [patientFiles, setPatientFiles] = useState([])
  
  const [newEvolution, setNewEvolution] = useState('')
  const [newPrescription, setNewPrescription] = useState({ type: 'receita', title: '', content: '' })
  const [selectedFile, setSelectedFile] = useState(null)
  const [fileCategory, setFileCategory] = useState('documento')
  const [isSubmitting, setIsSubmitting] = useState(false)

  useEffect(() => {
    if (patientId) {
      fetchEvolutions()
      fetchPrescriptions()
      fetchFiles()
    }
  }, [patientId])

  const fetchEvolutions = async () => {
    try {
      const res = await axios.get(`/api/patients/${patientId}/evolutions`)
      setEvolutions(res.data)
    } catch (err) {
      console.error(err)
    }
  }

  const fetchPrescriptions = async () => {
    try {
      const res = await axios.get(`/api/patients/${patientId}/prescriptions`)
      setPrescriptions(res.data)
    } catch (err) {
      console.error(err)
    }
  }

  const fetchFiles = async () => {
    try {
      const res = await axios.get(`/api/patients/${patientId}/files`)
      setPatientFiles(res.data)
    } catch (err) {
      console.error(err)
    }
  }

  const handleAddEvolution = async (e) => {
    e.preventDefault()
    if (!newEvolution.trim()) return
    setIsSubmitting(true)
    try {
      await axios.post(`/api/patients/${patientId}/evolutions`, { content: newEvolution })
      setNewEvolution('')
      fetchEvolutions()
      showAlert('Evolução registrada com sucesso!', 'success')
    } catch (err) {
      showAlert('Erro ao registrar evolução.', 'error')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleAddPrescription = async (e) => {
    e.preventDefault()
    if (!newPrescription.content.trim()) return
    setIsSubmitting(true)
    try {
      await axios.post(`/api/patients/${patientId}/prescriptions`, newPrescription)
      setNewPrescription({ type: 'receita', title: '', content: '' })
      fetchPrescriptions()
      showAlert('Prescrição registrada com sucesso!', 'success')
    } catch (err) {
      showAlert('Erro ao registrar prescrição.', 'error')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleFileUpload = async (e) => {
    e.preventDefault()
    if (!selectedFile) return
    
    const formData = new FormData()
    formData.append('file', selectedFile)
    formData.append('category', fileCategory)

    setIsSubmitting(true)
    try {
      await axios.post(`/api/patients/${patientId}/files`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      setSelectedFile(null)
      fetchFiles()
      showAlert('Arquivo enviado com sucesso!', 'success')
    } catch (err) {
      showAlert('Erro ao enviar arquivo.', 'error')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleDeleteEvolution = async (id) => {
    if (!confirm('Tem certeza que deseja excluir esta anotação?')) return
    try {
      await axios.delete(`/api/evolutions/${id}`)
      fetchEvolutions()
      showAlert('Evolução excluída.', 'success')
    } catch (err) {
      showAlert('Erro ou sem permissão para excluir.', 'error')
    }
  }

  const handleDeletePrescription = async (id) => {
    if (!confirm('Tem certeza que deseja excluir esta prescrição?')) return
    try {
      await axios.delete(`/api/prescriptions/${id}`)
      fetchPrescriptions()
      showAlert('Prescrição excluída.', 'success')
    } catch (err) {
      showAlert('Erro ou sem permissão para excluir.', 'error')
    }
  }

  const handleDeleteFile = async (id) => {
    if (!confirm('Tem certeza que deseja excluir este arquivo?')) return
    try {
      await axios.delete(`/api/files/${id}`)
      fetchFiles()
      showAlert('Arquivo excluído.', 'success')
    } catch (err) {
      showAlert('Erro ou sem permissão para excluir.', 'error')
    }
  }

  const printPrescription = (prescription) => {
    const printWindow = window.open('', '_blank')
    printWindow.document.write(`
      <html>
        <head>
          <title>${prescription.type === 'atestado' ? 'Atestado' : 'Receituário'} - ${prescription.title}</title>
          <style>
            body { font-family: sans-serif; padding: 40px; color: #333; }
            .header { text-align: center; margin-bottom: 50px; border-bottom: 2px solid #ccc; padding-bottom: 20px; }
            .content { font-size: 16px; white-space: pre-wrap; line-height: 1.6; min-height: 400px; }
            .footer { margin-top: 50px; text-align: center; border-top: 1px solid #ccc; padding-top: 20px; }
            .signature { margin-top: 80px; width: 300px; border-top: 1px solid #000; margin-left: auto; margin-right: auto; text-align: center; }
          </style>
        </head>
        <body>
          <div class="header">
            <h2>${prescription.type === 'atestado' ? 'Atestado Médico' : 'Receituário Médico'}</h2>
            <h3>${prescription.title || ''}</h3>
          </div>
          <div class="content">${prescription.content}</div>
          <div class="signature">
            ${prescription.user.name}<br/>
            CRM/CRO: ______________
          </div>
          <div class="footer">
            Data: ${new Date(prescription.created_at).toLocaleDateString('pt-BR')}
          </div>
        </body>
      </html>
    `)
    printWindow.document.close()
    printWindow.print()
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', height: '100%' }}>
      {/* Sub-Abas do Prontuário */}
      <div style={{ display: 'flex', gap: '1rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>
        <button 
          type="button" 
          onClick={() => setEmrTab('evolucao')} 
          style={{ ...subTabStyle, color: emrTab === 'evolucao' ? 'var(--accent)' : 'var(--text-secondary)', borderBottom: emrTab === 'evolucao' ? '2px solid var(--accent)' : 'none' }}
        >
          Evolução Clínica
        </button>
        <button 
          type="button" 
          onClick={() => setEmrTab('prescricoes')} 
          style={{ ...subTabStyle, color: emrTab === 'prescricoes' ? 'var(--accent)' : 'var(--text-secondary)', borderBottom: emrTab === 'prescricoes' ? '2px solid var(--accent)' : 'none' }}
        >
          Prescrições & Atestados
        </button>
        <button 
          type="button" 
          onClick={() => setEmrTab('arquivos')} 
          style={{ ...subTabStyle, color: emrTab === 'arquivos' ? 'var(--accent)' : 'var(--text-secondary)', borderBottom: emrTab === 'arquivos' ? '2px solid var(--accent)' : 'none' }}
        >
          Arquivos & Exames
        </button>
      </div>

      {/* Conteúdo: Evolução */}
      {emrTab === 'evolucao' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', height: '100%' }}>
          <div style={{ backgroundColor: 'var(--bg-tertiary)', padding: '1.5rem', borderRadius: '12px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
              <h4 style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', margin: 0 }}>Nova Evolução</h4>
              <span style={{ fontSize: '0.8rem', color: 'var(--accent)', fontWeight: 500, backgroundColor: 'rgba(59, 130, 246, 0.1)', padding: '0.2rem 0.6rem', borderRadius: '4px' }}>Dica: Digite / para usar modelos prontos</span>
            </div>
            
            <RichTextEditor 
              value={newEvolution} 
              onChange={setNewEvolution} 
            />
            
            <button type="button" onClick={handleAddEvolution} disabled={isSubmitting} className="btn-primary" style={{ marginTop: '1rem' }}>
              {isSubmitting ? 'Registrando...' : 'Registrar Evolução'}
            </button>
          </div>

          <div style={{ flex: 1, overflowY: 'auto' }}>
            <h4 style={{ marginBottom: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>Histórico de Evolução</h4>
            {evolutions.length === 0 ? (
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>Nenhuma evolução registrada.</p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                {evolutions.map(evo => (
                  <div key={evo.id} style={{ padding: '1.25rem', backgroundColor: 'var(--bg-secondary)', borderLeft: '4px solid var(--accent)', borderRadius: '8px', position: 'relative' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
                      <span style={{ fontWeight: 600, color: 'var(--text-primary)', fontSize: '0.9rem' }}>{evo.user?.name} ({evo.user?.role})</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                        <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', display: 'flex', alignItems: 'center', gap: '0.25rem' }}><Clock size={12}/> {new Date(evo.created_at).toLocaleString('pt-BR')}</span>
                        {(['owner', 'manager'].includes(user.role) || user.id === evo.user_id) && (
                          <button type="button" onClick={() => handleDeleteEvolution(evo.id)} style={{ color: '#ef4444', background: 'transparent', border: 'none', cursor: 'pointer' }} title="Excluir"><Trash2 size={14}/></button>
                        )}
                      </div>
                    </div>
                    <div className="rich-text-content" style={{ color: 'var(--text-primary)', fontSize: '0.95rem', lineHeight: 1.5 }} dangerouslySetInnerHTML={{ __html: evo.content }} />
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Conteúdo: Prescrições */}
      {emrTab === 'prescricoes' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', height: '100%' }}>
          <div style={{ backgroundColor: 'var(--bg-tertiary)', padding: '1.5rem', borderRadius: '12px' }}>
            <h4 style={{ marginBottom: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>Nova Prescrição / Atestado</h4>
            <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '1rem' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', color: 'var(--text-primary)', fontSize: '0.9rem' }}>
                <input type="radio" name="docType" checked={newPrescription.type === 'receita'} onChange={() => setNewPrescription({...newPrescription, type: 'receita'})} /> Receituário
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', color: 'var(--text-primary)', fontSize: '0.9rem' }}>
                <input type="radio" name="docType" checked={newPrescription.type === 'atestado'} onChange={() => setNewPrescription({...newPrescription, type: 'atestado'})} /> Atestado Médico
              </label>
            </div>
            <input 
              type="text" 
              placeholder={newPrescription.type === 'atestado' ? "Título (ex: Atestado de Comparecimento)" : "Título (ex: Receita de Antibiótico)"} 
              value={newPrescription.title} 
              onChange={e => setNewPrescription({...newPrescription, title: e.target.value})}
              style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-secondary)', color: 'var(--text-primary)', marginBottom: '1rem' }}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <span style={{ fontSize: '0.8rem', color: 'var(--accent)', fontWeight: 500, backgroundColor: 'rgba(59, 130, 246, 0.1)', padding: '0.2rem 0.6rem', borderRadius: '4px' }}>Dica: Digite / para usar modelos prontos</span>
            </div>
            <RichTextEditor 
              value={newPrescription.content} 
              onChange={content => setNewPrescription({...newPrescription, content})} 
            />
            <button type="button" onClick={handleAddPrescription} disabled={isSubmitting} className="btn-primary" style={{ marginTop: '1rem' }}>
              {isSubmitting ? 'Salvando...' : 'Salvar Documento'}
            </button>
          </div>

          <div style={{ flex: 1, overflowY: 'auto' }}>
            <h4 style={{ marginBottom: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>Histórico de Documentos</h4>
            {prescriptions.length === 0 ? (
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>Nenhum documento registrado.</p>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '1rem' }}>
                {prescriptions.map(pres => (
                  <div key={pres.id} style={{ padding: '1.25rem', backgroundColor: 'var(--bg-secondary)', border: '1px solid var(--border-color)', borderRadius: '8px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.25rem' }}>
                      <span style={{ padding: '0.15rem 0.5rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 600, backgroundColor: pres.type === 'atestado' ? 'rgba(239, 68, 68, 0.1)' : 'rgba(37, 99, 235, 0.1)', color: pres.type === 'atestado' ? '#ef4444' : 'var(--accent)' }}>
                        {pres.type === 'atestado' ? 'ATESTADO' : 'RECEITA'}
                      </span>
                      <h5 style={{ fontWeight: 600, color: 'var(--text-primary)', margin: 0, flex: 1 }}>
                        {pres.title || 'Documento sem título'}
                      </h5>
                      {(['owner', 'manager'].includes(user.role) || user.id === pres.user_id) && (
                        <button type="button" onClick={() => handleDeletePrescription(pres.id)} style={{ color: '#ef4444', background: 'transparent', border: 'none', cursor: 'pointer' }}><Trash2 size={14}/></button>
                      )}
                    </div>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginBottom: '1rem' }}>{new Date(pres.created_at).toLocaleDateString('pt-BR')} por {pres.user?.name}</p>
                    <div className="rich-text-content" style={{ color: 'var(--text-primary)', fontSize: '0.9rem', maxHeight: '80px', overflow: 'hidden', opacity: 0.8, marginBottom: '1rem' }} dangerouslySetInnerHTML={{ __html: pres.content }} />
                    <button type="button" onClick={() => printPrescription(pres)} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', background: 'transparent', border: 'none', color: 'var(--accent)', cursor: 'pointer', fontWeight: 500, fontSize: '0.9rem' }}>
                      <Printer size={16}/> Imprimir
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Conteúdo: Arquivos */}
      {emrTab === 'arquivos' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', height: '100%' }}>
          <div style={{ backgroundColor: 'var(--bg-tertiary)', padding: '1.5rem', borderRadius: '12px' }}>
            <h4 style={{ marginBottom: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>Anexar Novo Arquivo</h4>
            <div style={{ display: 'flex', gap: '1rem', alignItems: 'center', flexWrap: 'wrap' }}>
              <select 
                value={fileCategory}
                onChange={e => setFileCategory(e.target.value)}
                style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-secondary)', color: 'var(--text-primary)', minWidth: '150px' }}
              >
                <option value="documento">Documento (PDF, Word...)</option>
                <option value="imagem">Foto / Imagem Clínica</option>
                <option value="raio-x">Raio-X / Tomografia</option>
                <option value="exame">Exame Laboratorial</option>
                <option value="outros">Outros</option>
              </select>
              <input 
                type="file" 
                onChange={e => setSelectedFile(e.target.files[0])}
                style={{ color: 'var(--text-primary)', fontSize: '0.9rem', flex: 1 }}
              />
              <button type="button" onClick={handleFileUpload} disabled={isSubmitting || !selectedFile} className="btn-primary" style={{ padding: '0.75rem 1.5rem' }}>
                {isSubmitting ? 'Enviando...' : 'Fazer Upload'}
              </button>
            </div>
          </div>

          <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '2rem' }}>
            {patientFiles.length === 0 ? (
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>Nenhum arquivo anexado.</p>
            ) : (
              <>
                {/* Galeria de Imagens e Raio-X */}
                {(patientFiles.filter(f => ['imagem', 'raio-x'].includes(f.category)).length > 0) && (
                  <div>
                    <h4 style={{ marginBottom: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>Imagens Clínicas e Raio-X</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '1rem' }}>
                      {patientFiles.filter(f => ['imagem', 'raio-x'].includes(f.category)).map(file => (
                        <div key={file.id} style={{ display: 'flex', flexDirection: 'column', backgroundColor: 'var(--bg-secondary)', border: '1px solid var(--border-color)', borderRadius: '8px', overflow: 'hidden' }}>
                          <div style={{ height: '150px', backgroundColor: '#e5e7eb', position: 'relative' }}>
                            {file.file_type && file.file_type.startsWith('image/') ? (
                              <a href={`http://localhost:8000/storage/${file.file_path}`} target="_blank" rel="noreferrer">
                                <img 
                                  src={`http://localhost:8000/storage/${file.file_path}`} 
                                  alt={file.file_name}
                                  style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
                                />
                              </a>
                            ) : (
                              <div style={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#9ca3af' }}>
                                Sem visualização
                              </div>
                            )}
                          </div>
                          <div style={{ padding: '0.75rem', display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                            <p style={{ color: 'var(--text-primary)', fontWeight: 500, fontSize: '0.85rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={file.file_name}>{file.file_name}</p>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                              <span style={{ fontSize: '0.7rem', padding: '0.15rem 0.4rem', borderRadius: '4px', backgroundColor: file.category === 'raio-x' ? 'rgba(168, 85, 247, 0.1)' : 'rgba(59, 130, 246, 0.1)', color: file.category === 'raio-x' ? '#a855f7' : '#3b82f6', textTransform: 'uppercase', fontWeight: 600 }}>
                                {file.category}
                              </span>
                              {(['owner', 'manager'].includes(user.role) || user.role === 'doctor') && (
                                <button type="button" onClick={() => handleDeleteFile(file.id)} style={{ color: '#ef4444', background: 'transparent', border: 'none', cursor: 'pointer', padding: 0 }} title="Excluir">
                                  <Trash2 size={16}/>
                                </button>
                              )}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Lista de Documentos e Exames */}
                {(patientFiles.filter(f => !['imagem', 'raio-x'].includes(f.category)).length > 0) && (
                  <div>
                    <h4 style={{ marginBottom: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>Documentos e Exames Laboratoriais</h4>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                      {patientFiles.filter(f => !['imagem', 'raio-x'].includes(f.category)).map(file => (
                        <div key={file.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem', backgroundColor: 'var(--bg-secondary)', border: '1px solid var(--border-color)', borderRadius: '8px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                            <FilePlus size={20} color="var(--accent)" />
                            <div>
                              <p style={{ color: 'var(--text-primary)', fontWeight: 500, fontSize: '0.95rem' }}>{file.file_name}</p>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginTop: '0.25rem' }}>
                                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>{new Date(file.created_at).toLocaleString('pt-BR')}</span>
                                <span style={{ fontSize: '0.7rem', padding: '0.1rem 0.4rem', borderRadius: '4px', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-secondary)', textTransform: 'uppercase', fontWeight: 500 }}>
                                  {file.category || 'Documento'}
                                </span>
                              </div>
                            </div>
                          </div>
                          <div style={{ display: 'flex', gap: '1rem' }}>
                            <a href={`http://localhost:8000/storage/${file.file_path}`} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '0.25rem', fontSize: '0.9rem', textDecoration: 'none' }}>
                              <Download size={16}/> Baixar
                            </a>
                            {(['owner', 'manager'].includes(user.role) || user.role === 'doctor') && (
                              <button type="button" onClick={() => handleDeleteFile(file.id)} style={{ color: '#ef4444', background: 'transparent', border: 'none', cursor: 'pointer' }} title="Excluir"><Trash2 size={16}/></button>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

const subTabStyle = {
  background: 'transparent',
  border: 'none',
  fontSize: '0.95rem',
  fontWeight: 600,
  padding: '0.5rem 1rem',
  cursor: 'pointer',
  transition: 'all 0.2s'
}
