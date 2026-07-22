<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;

class SendDailyWhatsAppMessages extends Command
{
    /**
     * The name and signature of the console command.
     *
     * @var string
     */
    protected $signature = 'whatsapp:send-daily';

    /**
     * The console command description.
     *
     * @var string
     */
    protected $description = 'Dispara mensagens diárias de WhatsApp (aniversário, confirmação e recuperação)';

    /**
     * Execute the console command.
     */
    public function handle()
    {
        $this->info('Iniciando o disparo diário de mensagens do WhatsApp...');

        $this->sendBirthdayMessages();
        $this->sendConfirmationMessages();
        $this->sendRecoveryMessages();

        $this->info('Disparos finalizados.');
    }

    private function sendBirthdayMessages()
    {
        $template = \App\Models\Setting::getValue('whatsapp_template_birthday', 'Olá {nome}, a equipe da clínica deseja um Feliz Aniversário!');
        
        // Pessoas que fazem aniversário hoje (usando mês e dia)
        // OBS: Para funcionar bem em bancos locais como SQLite/MySQL precisamos lidar com datas com cuidado.
        // Vamos buscar todos e filtrar no PHP para garantir compatibilidade com SQLite (já que estamos no ambiente dev)
        $patients = \App\Models\Patient::all()->filter(function ($patient) {
            // Supondo que tenhamos a data de nascimento. Como não temos 'birth_date' na tabela de patient no momento
            // Vamos pular ou simular. Espera... a tabela patients não tem data de nascimento.
            // Para não quebrar, vamos checar se o campo existe.
            if (isset($patient->birth_date)) {
                $dob = \Carbon\Carbon::parse($patient->birth_date);
                return $dob->isBirthday();
            }
            return false;
        });

        foreach ($patients as $patient) {
            \App\Services\WhatsAppService::sendMessage($patient, 'birthday', $template);
            $this->info("Mensagem de aniversário enviada para: {$patient->name}");
        }
    }

    private function sendConfirmationMessages()
    {
        $template = \App\Models\Setting::getValue('whatsapp_template_confirmation', 'Olá {nome}, passando para lembrar da sua consulta amanhã às {hora}.');
        
        $tomorrowStart = now()->addDay()->startOfDay();
        $tomorrowEnd = now()->addDay()->endOfDay();

        $appointments = \App\Models\Appointment::with('patient')
            ->whereBetween('start_time', [$tomorrowStart, $tomorrowEnd])
            ->get();

        foreach ($appointments as $app) {
            if ($app->patient) {
                $hora = \Carbon\Carbon::parse($app->start_time)->format('H:i');
                \App\Services\WhatsAppService::sendMessage($app->patient, 'confirmation', $template, ['hora' => $hora]);
                $this->info("Mensagem de confirmação enviada para: {$app->patient->name}");
            }
        }
    }

    private function sendRecoveryMessages()
    {
        $template = \App\Models\Setting::getValue('whatsapp_template_recovery', 'Olá {nome}, notamos que sua última consulta foi cancelada. Gostaríamos de reagendar?');
        $days = (int) \App\Models\Setting::getValue('whatsapp_missed_return_days', 30);
        
        $targetDate = now()->subDays($days)->startOfDay();

        // Buscar consultas deletadas (canceladas) que ocorreram na data alvo (há 30 dias atrás)
        $cancelledAppointments = \App\Models\Appointment::withTrashed()
            ->with('patient')
            ->whereDate('start_time', $targetDate)
            ->whereNotNull('deleted_at')
            ->get();

        foreach ($cancelledAppointments as $app) {
            if ($app->patient) {
                // Verificar se ele tem alguma consulta DEPOIS dessa data
                $hasFuture = \App\Models\Appointment::where('patient_id', $app->patient->id)
                    ->where('start_time', '>', $targetDate)
                    ->exists();

                if (!$hasFuture) {
                    \App\Services\WhatsAppService::sendMessage($app->patient, 'recovery', $template);
                    $this->info("Mensagem de recuperação enviada para: {$app->patient->name}");
                }
            }
        }
    }
}
