# Backlog do CRM Clínico

Este documento armazena as ideias de funcionalidades, melhorias e requisitos futuros para o sistema. Ele servirá como um roteiro (roadmap) para as próximas etapas do desenvolvimento.

## Concluído / Em Produção (Últimos Updates)

✅ **Prontuário Eletrônico Básico**
✅ **Controle de Lixeira (Soft Deletes)**
✅ **Migração para Arquitetura SaaS (Multi-tenant) e Trial de 14 Dias**
✅ **Timer/Workflow de Consulta**: Botões de "Iniciar" e "Finalizar" integrados ao calendário para métricas reais de tempo de atendimento.
✅ **Rich Text Editor no Prontuário**: Suporte a edição de texto avançada (negrito, itálico, cores, tamanhos) e **autocompletar** para facilitar a digitação médica.
✅ **Ampliar Prontuário (Arquivos)**: Upload e anexação de imagens, raio-x e documentos.
✅ **Odontograma / Mapeamento**: Cancelado.
✅ **Idade do Paciente**: Exibir a idade calculada do paciente (em anos/meses) ao lado da data de nascimento em todas as telas de perfil e ficha médica.
✅ **Cálculo Inteligente de Encaixe (Smart Booking)**: Se uma consulta for finalizada mais cedo ou cancelada, o sistema calcula o tempo vago e sugere automaticamente encaixes da Fila de Espera para otimizar o fim do dia.
✅ **Criptografia de Ponta a Ponta**: Criptografar os históricos e prontuários (EMR) diretamente no banco de dados, protegendo informações sensíveis em caso de vazamentos. (Feito via Criptografia em Repouso do Laravel).
✅ **Visibilidade de Senhas (UX)**: Adicionar o ícone de "olho" (👁️) em todos os campos de senha do sistema para alternar a visibilidade da digitação. (Concluído)
✅ **Redundância e Offline (Local First)**: Criar mecanismos de contingência (ex: cache no navegador tipo PWA ou banco de dados secundário local) para que a clínica não perca dados ou pare de operar caso a internet caia temporariamente. (Concluído com Serwist, IndexedDB e SWR Queue)
✅ **Importador/Exportador**: Suportar a importação de planilhas Excel/CSV de outros sistemas (pacientes, histórico) e exportação de dados do CRM. (Concluído)
✅ **Deploy Automatizado (Vercel & Render)**: Configuração de build automatizado do Flutter Web na Vercel (`build.sh` + `vercel.json`) integrado com a API Backend Go no Render (`https://crm-clinica-gjss.onrender.com`).
## 🎯 Próximas Funcionalidades / Prioridades

## Principais
- [x] **Modo escuro**: criar modo escuro para todo sistema, com botão no topbar. 
- [ ] **API do Google Calendar**: Criar integração com a API do Google Calendar para sincronização de consultas (criar, editar, cancelar).
- [x] **Sistema de pagamento**: criar sistema junto com apis de pagamentos (Stripe, Pagseguro, Mercado Pago), criar planos de assinatura (Trial 14 dias, Mensal, Anual) e métricas de faturamento mensal para o SaaS.
- [x] **Sistema de Cupons de desconto**: Sistema de cupons de descontos para planos do SaaS, os cupons são em porcentagens, o responsavel/dono do cupom vai ganhar em cima de clinicas indicadas que usaram o cupom.
- [x] **Módulo Financeiro**: Registro de pagamentos, parcelamentos, recibos e relatório de receitas do mês, para clinica.
- [x] **Módulo de Estoque**: Registro de consumo de materiais por consulta (ex: luvas, anestésicos).
- [x] **Armazenamento em Nuvem**: Migrar anexos de `local` para Supabase Storage (Bucket).
- [x] **Dashboard Gerencial com Gráficos**: Tela inicial com gráficos, estatísticas de atendimento, taxa de faltas e faturamento.

## Segundaria
- [ ] **Autenticação em Duas Etapas (2FA)**: Opção de dupla autenticação no login para médicos e gerentes.
- [ ] **Sistema de Notificações (WhatsApp)**: Reimplementar integração com envio de mensagens automáticas (agendamento, retornos) via WhatsApp.
- [ ] **Módulo de Prescrições**: Reimplementar o sistema de geração e impressão de prescrições e atestados com rich text.
- [ ] **API de Assinatura Digital**: Integração com APIs de prescrição eletrônica e atestados (ex: ICP-Brasil, Memed, CFM) para validade legal.
- [ ] **API Oficial de WhatsApp**: Substituir/melhorar o robô local conectando a um provedor profissional de WhatsApp (ex: Z-API, Evolution API, Baileys) para envios mais estáveis e interativos.
- [ ] **Validação Externa de CNPJ**: Analisar a viabilidade de integração com APIs públicas (ex: BrasilAPI/ReceitaWS) no momento do cadastro (SaaS).
