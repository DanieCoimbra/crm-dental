-- Migration: 20260904000002_multi_tenancy_and_rls.sql
-- Description: Índices de performance por clinic_id e políticas de Row Level Security (RLS)

-- 1. Habilitação de Índices
CREATE INDEX IF NOT EXISTS idx_users_clinic ON public.users(clinic_id);
CREATE INDEX IF NOT EXISTS idx_patients_clinic ON public.patients(clinic_id);
CREATE INDEX IF NOT EXISTS idx_appointments_clinic ON public.appointments(clinic_id);
CREATE INDEX IF NOT EXISTS idx_appointments_doctor ON public.appointments(doctor_id);
CREATE INDEX IF NOT EXISTS idx_appointments_patient ON public.appointments(patient_id);
CREATE INDEX IF NOT EXISTS idx_clinical_evolutions_clinic ON public.clinical_evolutions(clinic_id);
CREATE INDEX IF NOT EXISTS idx_clinical_notes_clinic ON public.clinical_notes(clinic_id);
CREATE INDEX IF NOT EXISTS idx_transactions_clinic ON public.clinic_transactions(clinic_id);

-- 2. Habilitação de RLS em tabelas multi-tenant
ALTER TABLE public.clinics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patients ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.appointments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.clinical_evolutions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.clinical_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.medical_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.clinic_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

-- 3. Políticas de RLS Permissivas para o Serviço Backend Go (gorm / postgres role)
-- Permite acesso total para a role autenticada de conexão do backend (service_role ou postgres)
CREATE POLICY "Allow Full Access to Backend Service" ON public.clinics FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.users FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.patients FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.appointments FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.clinical_evolutions FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.clinical_notes FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.medical_documents FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.clinic_transactions FOR ALL TO postgres USING (true) WITH CHECK (true);
CREATE POLICY "Allow Full Access to Backend Service" ON public.audit_logs FOR ALL TO postgres USING (true) WITH CHECK (true);
