<?php

namespace App\Http\Controllers;

use App\Models\Appointment;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use App\Models\Setting;
use App\Services\HolidayService;

class AppointmentController extends Controller
{
    public function index()
    {
        return response()->json(Appointment::with(['doctor.currentRoom', 'patient', 'room'])->get());
    }

    public function store(Request $request)
    {
        $request->validate([
            'doctor_id' => 'required|exists:users,id',
            'patient_id' => 'required|exists:patients,id',
            'appointment_type_id' => 'required|exists:appointment_types,id',
            'start_time' => 'required|date',
            'notes' => 'nullable|string',
        ], [
            'appointment_type_id.required' => 'Selecione o tipo de atendimento.',
            'doctor_id.required' => 'Selecione o médico.',
            'patient_id.required' => 'Selecione o paciente.',
            'start_time.required' => 'A data e hora são obrigatórias.'
        ]);

        $startTime = Carbon::parse($request->start_time);
        $type = \App\Models\AppointmentType::findOrFail($request->appointment_type_id);
        $endTime = $startTime->copy()->addMinutes($type->duration_minutes);

        if ($startTime->isPast()) {
            return response()->json([
                'message' => 'Não é possível agendar uma consulta no passado.'
            ], 400);
        }

        // Validação de Configurações da Clínica
        $allowWeekends = Setting::getValue('allow_weekends', false);
        $allowOutOfHours = Setting::getValue('allow_out_of_hours', false);
        $allowHolidays = Setting::getValue('allow_holidays', false);
        $allowedDates = Setting::getValue('custom_allowed_dates', []);
        
        $isAllowedDate = in_array($startTime->format('Y-m-d'), $allowedDates);

        if (!$isAllowedDate) {
            if (!$allowWeekends && $startTime->isWeekend()) {
                return response()->json(['message' => 'O agendamento aos finais de semana está desabilitado pela gerência.'], 400);
            }

            if (!$allowHolidays && HolidayService::isHoliday($startTime)) {
                return response()->json(['message' => 'O agendamento em feriados está desabilitado.'], 400);
            }
        }

        if (!$allowOutOfHours && ($startTime->hour < 8 || $startTime->hour >= 18)) {
            return response()->json(['message' => 'O agendamento fora do horário comercial (08:00 - 18:00) está desabilitado.'], 400);
        }

        // Verificação de Conflitos do Médico
        $conflict = Appointment::where('doctor_id', $request->doctor_id)
            ->where(function($query) use ($startTime, $endTime) {
                $query->whereBetween('start_time', [$startTime, $endTime])
                      ->orWhereBetween('end_time', [$startTime, $endTime])
                      ->orWhere(function($q) use ($startTime, $endTime) {
                          $q->where('start_time', '<=', $startTime)
                            ->where('end_time', '>=', $endTime);
                      });
            })->first();

        if ($conflict) {
            return response()->json([
                'message' => 'O médico já possui uma consulta neste horário.',
                'conflict' => $conflict
            ], 409);
        }

        $appointment = Appointment::create([
            'doctor_id' => $request->doctor_id,
            'patient_id' => $request->patient_id,
            'appointment_type_id' => $request->appointment_type_id,
            'start_time' => $startTime,
            'end_time' => $endTime,
            'notes' => $request->notes,
        ]);

        return response()->json($appointment, 201);
    }

    public function update(Request $request, Appointment $appointment)
    {
        if (auth()->user()->role === 'doctor') {
            return response()->json(['message' => 'Médicos não têm permissão para editar agendamentos.'], 403);
        }

        $request->validate([
            'patient_id' => 'sometimes|exists:patients,id',
            'doctor_id' => 'sometimes|exists:users,id',
            'room_id' => 'nullable|exists:rooms,id',
            'appointment_type_id' => 'sometimes|exists:appointment_types,id',
            'start_time' => 'sometimes|date',
            'status' => 'sometimes|in:scheduled,completed,cancelled',
            'notes' => 'nullable|string'
        ]);

        $startTime = $request->has('start_time') ? Carbon::parse($request->start_time) : Carbon::parse($appointment->start_time);
        
        $typeId = $request->has('appointment_type_id') ? $request->appointment_type_id : $appointment->appointment_type_id;
        $type = \App\Models\AppointmentType::findOrFail($typeId);
        $durationMinutes = $type->duration_minutes;
        
        $endTime = $startTime->copy()->addMinutes($durationMinutes);

        // Se a data/hora mudou, verifica se é no passado
        if ($startTime->notEqualTo(Carbon::parse($appointment->start_time)) && $startTime->isPast()) {
            return response()->json([
                'message' => 'Não é possível reagendar para o passado.'
            ], 400);
        }

        // Validação de Configurações da Clínica (se a data mudou)
        if ($startTime->notEqualTo(Carbon::parse($appointment->start_time))) {
            $allowWeekends = Setting::getValue('allow_weekends', false);
            $allowOutOfHours = Setting::getValue('allow_out_of_hours', false);
            $allowHolidays = Setting::getValue('allow_holidays', false);
            $allowedDates = Setting::getValue('custom_allowed_dates', []);
            
            $isAllowedDate = in_array($startTime->format('Y-m-d'), $allowedDates);

            if (!$isAllowedDate) {
                if (!$allowWeekends && $startTime->isWeekend()) {
                    return response()->json(['message' => 'O agendamento aos finais de semana está desabilitado pela gerência.'], 400);
                }

                if (!$allowHolidays && HolidayService::isHoliday($startTime)) {
                    return response()->json(['message' => 'O agendamento em feriados está desabilitado.'], 400);
                }
            }

            if (!$allowOutOfHours && ($startTime->hour < 8 || $startTime->hour >= 18)) {
                return response()->json(['message' => 'O agendamento fora do horário comercial (08:00 - 18:00) está desabilitado.'], 400);
            }
        }

        // Verificação de Conflitos do Médico (ignorando a própria consulta)
        $conflict = Appointment::where('doctor_id', $request->doctor_id ?? $appointment->doctor_id)
            ->where('id', '!=', $appointment->id)
            ->where(function($query) use ($startTime, $endTime) {
                $query->whereBetween('start_time', [$startTime, $endTime])
                      ->orWhereBetween('end_time', [$startTime, $endTime])
                      ->orWhere(function($q) use ($startTime, $endTime) {
                          $q->where('start_time', '<=', $startTime)
                            ->where('end_time', '>=', $endTime);
                      });
            })->first();

        if ($conflict) {
            return response()->json([
                'message' => 'O médico já possui uma consulta neste horário.',
                'conflict' => $conflict
            ], 409);
        }

        $appointment->update([
            'patient_id' => $request->patient_id ?? $appointment->patient_id,
            'doctor_id' => $request->doctor_id ?? $appointment->doctor_id,
            'room_id' => $request->has('room_id') ? $request->room_id : $appointment->room_id,
            'appointment_type_id' => $typeId,
            'start_time' => $startTime,
            'end_time' => $endTime,
            'status' => $request->status ?? $appointment->status,
            'notes' => $request->has('notes') ? $request->notes : $appointment->notes,
        ]);

        return response()->json($appointment);
    }

    public function destroy(Appointment $appointment, \App\Services\SmartBookingService $smartBookingService)
    {
        if (auth()->user()->role === 'doctor') {
            return response()->json(['message' => 'Médicos não têm permissão para cancelar agendamentos.'], 403);
        }

        \App\Services\TrashService::checkLimit();

        // Calcular gap (tamanho total da consulta cancelada)
        $startTime = \Carbon\Carbon::parse($appointment->start_time);
        $endTime = \Carbon\Carbon::parse($appointment->end_time);
        
        $suggestions = collect();
        // Só busca sugestões se o cancelamento for de hoje em diante
        if ($startTime->isToday() || $startTime->isFuture()) {
            $suggestions = $smartBookingService->findSuggestionsForGap($appointment->doctor_id, $startTime, $endTime);
        }

        $appointment->delete();
        
        return response()->json([
            'message' => 'Agendamento cancelado com sucesso.',
            'smart_suggestions' => $suggestions
        ]);
    }

    public function start(Appointment $appointment)
    {
        // Verifica se o médico já tem outra consulta em andamento
        $inProgress = Appointment::where('doctor_id', $appointment->doctor_id)
            ->where('status', 'in_progress')
            ->where('id', '!=', $appointment->id)
            ->exists();

        if ($inProgress) {
            return response()->json([
                'message' => 'O médico já possui um atendimento em andamento. Finalize-o antes de iniciar outro.'
            ], 400);
        }

        $appointment->update([
            'status' => 'in_progress',
            'actual_start_time' => Carbon::now(),
        ]);

        return response()->json($appointment);
    }

    public function finish(Appointment $appointment, \App\Services\SmartBookingService $smartBookingService)
    {
        $now = Carbon::now();
        $scheduledEndTime = Carbon::parse($appointment->end_time);
        
        $appointment->update([
            'status' => 'completed',
            'actual_end_time' => $now,
        ]);

        $suggestions = collect();
        // Se terminou mais cedo, calcula o gap do momento atual até o fim previsto
        if ($now->lessThan($scheduledEndTime)) {
            $suggestions = $smartBookingService->findSuggestionsForGap($appointment->doctor_id, $now, $scheduledEndTime);
        }

        return response()->json([
            'appointment' => $appointment,
            'smart_suggestions' => $suggestions
        ]);
    }
}
