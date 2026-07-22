<?php

namespace App\Services;

use App\Models\WhatsAppLog;
use Illuminate\Support\Facades\Log;

class WhatsAppService
{
    /**
     * Simula o envio de uma mensagem de WhatsApp
     *
     * @param \App\Models\Patient $patient O paciente destino
     * @param string $type O tipo de mensagem ('birthday', 'confirmation', 'recovery')
     * @param string $template O template da mensagem contendo variáveis ex: {nome}
     * @param array $data Dados extras para o replace (ex: ['hora' => '14:30'])
     * @return bool
     */
    public static function sendMessage($patient, $type, $template, $data = [])
    {
        // Garante que a clínica não tentará enviar para um paciente sem telefone
        if (empty($patient->phone)) {
            Log::warning("WhatsAppService: Paciente {$patient->id} sem telefone cadastrado.");
            return false;
        }

        // Formatação do Template
        $message = str_replace('{nome}', $patient->name, $template);
        
        foreach ($data as $key => $value) {
            $message = str_replace('{' . $key . '}', $value, $message);
        }

        // -------------------------------------------------------------
        // TODO: Integração de API Futura
        // Aqui entraria o GuzzleHttp chamando a Z-API, Evolution, etc.
        // ex: Http::post('https://api.whatsapp...', ['number' => $patient->phone, 'msg' => $message]);
        // -------------------------------------------------------------

        // Por enquanto, simulamos o sucesso gravando no banco
        WhatsAppLog::create([
            'patient_id' => $patient->id,
            'phone_number' => $patient->phone,
            'message_type' => $type,
            'message_body' => $message,
            'status' => 'sent',
            'sent_at' => now(),
        ]);

        return true;
    }
}
