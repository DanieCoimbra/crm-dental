# Plano de Implementação: Modo Escuro (Dark Mode System-Wide)

## 1. Visão Geral
Implementar um sistema de suporte a Tema Escuro (Dark Mode) global em toda a aplicação (Frontend Flutter e persistência de preferência no Backend Go). O modo escuro visa proporcionar maior conforto visual aos profissionais de saúde durante atendimentos em ambientes com iluminação reduzida, além de conferir uma estética moderna e executiva ao CRM.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Frontend:** Flutter Material Design 3 (`ThemeData`, `ColorScheme.dark()`, `ThemeMode`), Riverpod (`StateNotifierProvider` / `NotifierProvider`), `Hive` ou `flutter_secure_storage` para armazenar preferência offline local.
- **Backend:** Go 1.26 + Fiber v2, PostgreSQL (GORM) para persistência do perfil de preferência do usuário.
- **Arquitetura & Clean Code:** Injeção de dependência via Riverpod no Flutter e arquitetura em camadas (Core/Adapters) no Go.

---

## 3. Regras de Negócio
1. **Persistência Híbrida (Local First + Sync Cloud):**
   - O estado do tema deve ser alterado instantaneamente na UI (sem lag).
   - A preferência é armazenada localmente via cache (`Hive`) e sincronizada de forma assíncrona com o perfil do usuário no servidor backend Go.
2. **Opções de Configuração:**
   - `Claro` (Light Mode)
   - `Escuro` (Dark Mode)
   - `Sistema` (Acompanhar as configurações nativas do SO/Navegador)
3. **Padrão Visual:**
   - Seguir paleta Tailwind/Material 3 HSL tailored (fundos escuros profundos como `#111827` / `#1F2937` em vez de preto puro, mantendo contraste acessível com textos `#F9FAFB`).
   - Manter a cor de destaque principal da clínica (Accent Brand Color) legível e vibrante em ambos os modos.

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Data Model (`internal/core/domain/user.go`)
Adicionar a coluna `ThemePreference` no modelo de usuário:
```go
type User struct {
    ID              uint      `gorm:"primaryKey" json:"id"`
    ClinicID        uint      `gorm:"index" json:"clinic_id"`
    Name            string    `json:"name"`
    Email           string    `gorm:"uniqueIndex" json:"email"`
    ThemePreference string    `gorm:"default:'system'" json:"theme_preference"` // 'light', 'dark', 'system'
    UpdatedAt       time.Time `json:"updated_at"`
}
```

#### API Endpoint (`internal/adapters/http/user_handler.go`)
- `PUT /api/v1/users/preferences`
  - Payload: `{ "theme_preference": "dark" }`
  - Resposta: `200 OK` com dados atualizados do usuário.

---

### 4.2. Frontend (Flutter)

#### Theme State Provider (`lib/core/theme/theme_provider.dart`)
```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hive_flutter/hive_flutter.dart';

enum AppThemeMode { light, dark, system }

final themeProvider = NotifierProvider<ThemeNotifier, ThemeMode>(ThemeNotifier.new);

class ThemeNotifier extends Notifier<ThemeMode> {
  static const String _key = 'theme_preference';

  @override
  ThemeMode build() {
    final box = Hive.box('settings');
    final savedTheme = box.get(_key, defaultValue: 'system');
    return _parseThemeMode(savedTheme);
  }

  Future<void> setTheme(ThemeMode mode) async {
    state = mode;
    final box = Hive.box('settings');
    await box.put(_key, mode.name);
    // Disparar atualização assíncrona para API Go via Dio (sem bloquear a UI)
  }

  ThemeMode _parseThemeMode(String name) {
    switch (name) {
      case 'dark':
        return ThemeMode.dark;
      case 'light':
        return ThemeMode.light;
      default:
        return ThemeMode.system;
    }
  }
}
```

#### Color Palettes (`lib/core/theme/app_theme.dart`)
Definição detalhada dos temas Claro e Escuro utilizando Material 3 ColorScheme:
- Light Palette: Background `#F8FAFC`, Surface `#FFFFFF`, Primary `#2563EB`, Text `#0F172A`.
- Dark Palette: Background `#0F172A`, Surface `#1E293B`, Primary `#3B82F6`, Text `#F8FAFC`.

#### Header Toggle Widget (`lib/shared/widgets/theme_toggle_button.dart`)
- Ícone dinâmico na TopBar/AppBar (`IconButton`) alternando entre Sol (`Icons.wb_sunny_outlined`) e Lua (`Icons.dark_mode_outlined`).

---

## 5. Plano de Testes & Validação
1. **Teste Unitário (Flutter):** Verificando se a alteração do `ThemeNotifier` atualiza o estado e salva a chave no `Hive`.
2. **Teste de Integração API (Go):** Requisição PUT para `/users/preferences` validando atualização no banco PostgreSQL.
3. **Validação Visual (UX):** Testar legibilidade de tabelas, formulários, modais e prontuário em ambos os modos.
