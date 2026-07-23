# Technical Spec: F10-dark-mode

## 1. Technical Overview
- **Feature**: Tema e Modo Escuro Global
- **Tech Stack Used**: Flutter, Riverpod, `shared_preferences` / `flutter_secure_storage`.
- **Architecture Approach**: Padrão de estado reativo, aplicando as cores através do `MaterialApp.theme`.

## 2. Data Models & Schema
- **State Management**:
  - `ThemeProvider`: Controla o `ThemeMode` (light, dark, system).

## 3. Component Architecture
- `ThemeToggleButton`
  - **Responsibility**: Ícone 🌞 / 🌙 na navbar para troca rápida de estado.
- **Global**: Toda a UI usa chamadas de cor via `Theme.of(context).colorScheme.surface` ao invés de cores fixas `Colors.white`.

## 4. Core Logic & Algorithms
- **Operation**: Inversão do Tema
  - Step 1: Usuário clica no Toggle.
  - Step 2: `ThemeProvider` altera o state.
  - Step 3: Salva o novo valor `'dark'` no armazenamento local.
  - Step 4: UI é reconstruída automaticamente pela reatividade do Riverpod.

## 5. Error Handling & Edge Cases
- **Scenario**: Primeira execução do app.
  - **Handling**: Usa o `ThemeMode.system` (Padrão do OS).

## 6. Security & Performance
- **Performance Targets**: Tempo de inversão instantâneo (<16ms frame), evitando builds lentos.
