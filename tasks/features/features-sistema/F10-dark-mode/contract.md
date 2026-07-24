# Contract: F10-dark-mode

## 1. Overview
Mantém a aplicação adaptável e persistente para os estilos Claro e Escuro, não envolvendo banco de dados ou requisições API.

## 2. Inputs (Local)
- O estado de `ThemeMode` via `SharedPreferences`.

## 3. Outputs (UI Events)
- Riverpod emite o `ThemeData` para o raiz `MaterialApp`.

## 4. Integration Rules
- Componentes da Agenda (Syncfusion) devem usar explicitamente as cores do contexto (`Theme.of(context).cardColor`) no invés de cores hardcoded.

## 5. Boundaries
- Não há suporte a personalização profunda de tema (ex: trocar paleta azul para verde livremente); a feature se limita a alternar fundos (Light/Dark).
