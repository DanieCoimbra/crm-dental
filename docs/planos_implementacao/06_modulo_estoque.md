# Plano de Implementação: Módulo de Estoque e Consumo de Insumos

## 1. Visão Geral
Implementar o Módulo de Controle de Estoque para a clínica odontológica. O módulo gerencia o catálogo de produtos e materiais clínicos (ex: luvas, anestésicos, resinas, agulhas, sugadores), permite movimentações manuais de entrada/saída, baixa automática de estoque vinculada à realização de consultas/procedimentos e alertas de estoque baixo na recepção.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, PostgreSQL (GORM), Triggers / UseCases acoplados ao evento de `FinishAppointment`.
- **Frontend:** Flutter + Riverpod, badges de notificação em tempo real na TopBar, filtros de busca por categoria.
- **Engenharia de Software:** Event-Driven Internal Messaging / Hooks no Go para dedução assíncrona ou síncrona de materiais sem bloquear a consulta.

---

## 3. Regras de Negócio
1. **Catálogo de Insumos (Produtos):**
   - Nome, Categoria (Descartáveis, Anestésicos, Ortodontia, Resinas, Cirurgia), Unidade de Medida (Caixa, Unidade, ml, Par), Preço de Custo, Quantidade Atual e Quantidade Mínima para Alerta.
2. **Baixa Automática por Procedimento (Receita de Insumos):**
   - É possível associar uma lista de insumos recomendados a cada tipo de procedimento odontológico.
   - Exemplo: Procedimento `Restauração em Resina`:
     - 1x Par de Luvas
     - 1x Tubete de Anestésico
     - 1x Bisnaga Resina A2 (0.2ml)
   - Ao clicar no botão **"Finalizar Atendimento"** da consulta (Timer de Atendimento), o sistema baixa automaticamente esses insumos do estoque da clínica.
3. **Movimentação Manual (Entradas e Ajustes):**
   - Registro de nota fiscal/compra para adicionar saldo ao estoque.
   - Registro de perdas/descartes de materiais vencidos ou danificados.
4. **Alertas de Estoque Crítico:**
   - Quando `quantidade_atual <= quantidade_minima`, o item entra na lista de alerta e gera uma notificação destacada no topo do sistema.

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Data Models (`internal/core/domain/inventory.go`)
```go
type Product struct {
    ID           uint            `gorm:"primaryKey" json:"id"`
    ClinicID     uint            `gorm:"index;not null" json:"clinic_id"`
    Name         string          `json:"name"`
    Category     string          `json:"category"`
    Unit         string          `json:"unit"` // "cx", "un", "ml"
    CurrentStock float64         `json:"current_stock"`
    MinimumStock float64         `json:"minimum_stock"`
    CostPrice    int64           `json:"cost_price_cents"`
    CreatedAt    time.Time       `json:"created_at"`
    Movements    []StockMovement `gorm:"foreignKey:ProductID" json:"movements,omitempty"`
}

type ProcedureMaterial struct {
    ID          uint    `gorm:"primaryKey" json:"id"`
    ProcedureID uint    `gorm:"index;not null" json:"procedure_id"`
    ProductID   uint    `gorm:"index;not null" json:"product_id"`
    Quantity    float64 `json:"quantity"`
}

type StockMovement struct {
    ID            uint      `gorm:"primaryKey" json:"id"`
    ClinicID      uint      `gorm:"index;not null" json:"clinic_id"`
    ProductID     uint      `gorm:"index;not null" json:"product_id"`
    Type          string    `json:"type"` // "in" (compra), "out" (consumo/perda), "auto" (consulta)
    Quantity      float64   `json:"quantity"`
    Reason        string    `json:"reason"`
    AppointmentID *uint     `gorm:"index" json:"appointment_id"`
    CreatedAt     time.Time `json:"created_at"`
}
```

#### Dedução Automática no Finish Appointment (`internal/core/usecase/appointment_usecase.go`)
```go
func (uc *AppointmentUseCase) FinishAppointment(ctx context.Context, appointmentID uint) error {
    // 1. Atualizar status da consulta para "completed"
    // 2. Buscar procedimentos vinculados à consulta
    // 3. Buscar materiais associados aos procedimentos
    // 4. Executar transação de baixa em Product.CurrentStock e gravar StockMovement
}
```

---

### 4.2. Frontend (Flutter)

#### Módulo de Estoque (`lib/features/inventory/presentation/`)
- **Tela de Catálogo de Materiais (`inventory_list_screen.dart`):**
  - Tabela responsiva com barra de busca, filtro por Categoria e indicador colorido de estoque:
    - **Verde:** OK.
    - **Amarelo/Vermelho:** Abaixo do Mínimo.
- **Modal de Movimentação de Estoque (`stock_movement_dialog.dart`):**
  - Form para registrar entrada por nota fiscal ou ajuste manual.
- **Configuração de Materiais por Procedimento (`procedure_material_screen.dart`):**
  - Vincular quais e quantos insumos são gastos por padrão em cada tipo de tratamento.
- **Alerta no Header:**
  - Ícone de Caixa / Estoque no topo com badge de quantidade de itens em nível crítico.

---

## 5. Plano de Testes & Validação
1. **Teste de Unidade (Dedução de Estoque):** Verificar se o encerramento da consulta atualiza corretamente a coluna `current_stock` e gera o `StockMovement`.
2. **Teste de Validação de Estoque Negativo:** Impedir que movimentações manuais incorretas deixem o estoque em valor inconsistente sem aviso.
3. **Teste da UI Flutter:** Simular alteração do catálogo e verificar se o badge de alerta na TopBar atualiza dinamicamente via Riverpod.
