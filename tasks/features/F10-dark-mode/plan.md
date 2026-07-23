# Action Plan: F10-dark-mode

## 1. Local Scope
- **Derivation**: Extracted from [PRD Dental CRM Fullstack](../../prd-dental-crm-fullstack.md) (US-009).
- **Responsibility**: Habilitar a inversão global da paleta de cores para um modo noturno persistido no dispositivo do usuário.

## 2. External Dependencies (Before starting)
- Trata-se de uma feature exclusivamente Frontend (Flutter). Depende do pacote `flutter_secure_storage` ou `shared_preferences` para salvar a opção.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts]
  - Mapear esquema de cores dark no `app_theme.dart` (Material 3).
- **Phase 2**: [Local Spec & Logic]
  - Criar um StateNotifier (`ThemeProvider`) via Riverpod responsável por gerir `ThemeMode`.
  - Injetar no `MaterialApp.router`.
- **Phase 3**: [Integration]
  - Adicionar o `ThemeToggleButton` na barra superior (Topbar).
  - Revisar telas antigas (Agenda, EMR) para confirmar se não há hardcodes (como `Colors.white`) quebrando a legibilidade no modo escuro.

## 4. Next Steps
- Run `/spec-write` in this folder to detail the Theme Provider architecture.
