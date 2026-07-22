<?php

namespace App\Http\Controllers;

use App\Models\Setting;
use Illuminate\Http\Request;
use App\Services\HolidayService;

class SettingController extends Controller
{
    public function index()
    {
        return response()->json([
            'allow_weekends' => Setting::getValue('allow_weekends', false),
            'allow_out_of_hours' => Setting::getValue('allow_out_of_hours', false),
            'allow_holidays' => Setting::getValue('allow_holidays', false),
            'custom_holidays' => Setting::getValue('custom_holidays', []),
            'custom_allowed_dates' => Setting::getValue('custom_allowed_dates', []),
            'whatsapp_missed_return_days' => Setting::getValue('whatsapp_missed_return_days', 30),
            'whatsapp_template_birthday' => Setting::getValue('whatsapp_template_birthday', 'Olá {nome}, a equipe da clínica deseja um Feliz Aniversário!'),
            'whatsapp_template_confirmation' => Setting::getValue('whatsapp_template_confirmation', 'Olá {nome}, passando para lembrar da sua consulta amanhã às {hora}.'),
            'whatsapp_template_recovery' => Setting::getValue('whatsapp_template_recovery', 'Olá {nome}, notamos que sua última consulta foi cancelada. Gostaríamos de reagendar?'),
        ]);
    }

    public function update(Request $request)
    {
        // Apenas gerentes
        if (auth()->user()->role !== 'manager') {
            return response()->json(['message' => 'Acesso negado.'], 403);
        }

        $request->validate([
            'allow_weekends' => 'nullable|boolean',
            'allow_out_of_hours' => 'nullable|boolean',
            'allow_holidays' => 'nullable|boolean',
            'custom_holidays' => 'nullable|array',
            'custom_holidays.*' => 'date_format:Y-m-d',
            'custom_allowed_dates' => 'nullable|array',
            'custom_allowed_dates.*' => 'date_format:Y-m-d',
            'whatsapp_missed_return_days' => 'nullable|integer|min:1',
            'whatsapp_template_birthday' => 'nullable|string',
            'whatsapp_template_confirmation' => 'nullable|string',
            'whatsapp_template_recovery' => 'nullable|string'
        ]);

        \Illuminate\Support\Facades\DB::transaction(function () use ($request) {
            $fields = [
                'allow_weekends', 'allow_out_of_hours', 'allow_holidays', 
                'custom_holidays', 'custom_allowed_dates', 
                'whatsapp_missed_return_days', 'whatsapp_template_birthday', 
                'whatsapp_template_confirmation', 'whatsapp_template_recovery'
            ];

            foreach ($fields as $field) {
                if ($request->exists($field)) {
                    Setting::setValue($field, $request->input($field));
                }
            }
        });

        return response()->json(['message' => 'Configurações atualizadas com sucesso.']);
    }

    public function holidays(Request $request)
    {
        $year = $request->get('year', date('Y'));
        return response()->json(HolidayService::getHolidaysForYear((int) $year));
    }
}
