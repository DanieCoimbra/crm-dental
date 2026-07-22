<?php

namespace Database\Seeders;

use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;

class AppointmentTypeSeeder extends Seeder
{
    /**
     * Run the database seeds.
     */
    public function run(): void
    {
        $type1 = \App\Models\AppointmentType::create([
            'name' => 'Consulta',
            'duration_minutes' => 30,
            'color' => '#3b82f6' // Azul
        ]);

        $type2 = \App\Models\AppointmentType::create([
            'name' => 'Cirurgia',
            'duration_minutes' => 120,
            'color' => '#ef4444' // Vermelho
        ]);

        $type3 = \App\Models\AppointmentType::create([
            'name' => 'Manutenção Aparelho',
            'duration_minutes' => 45,
            'color' => '#10b981' // Verde
        ]);

        // Populating 10 appointments
        $doctors = \App\Models\User::where('role', 'dentist')->get();
        $patients = \App\Models\Patient::all();
        $rooms = \App\Models\Room::all();
        $types = [$type1->id, $type2->id, $type3->id];

        if ($doctors->isEmpty() || $patients->isEmpty()) {
            return; // Not enough data
        }

        $now = now()->nextWeekday()->setHour(9)->setMinute(0)->setSecond(0);

        for ($i = 0; $i < 10; $i++) {
            $typeId = $types[array_rand($types)];
            $duration = \App\Models\AppointmentType::find($typeId)->duration_minutes;

            \App\Models\Appointment::create([
                'doctor_id' => $doctors->random()->id,
                'patient_id' => $patients->random()->id,
                'room_id' => $rooms->isNotEmpty() ? $rooms->random()->id : null,
                'appointment_type_id' => $typeId,
                'start_time' => $now->copy(),
                'end_time' => $now->copy()->addMinutes($duration),
                'status' => 'scheduled'
            ]);

            $now->addHours(2);
            if ($now->hour >= 16) {
                $now->addDay()->setHour(9);
                if ($now->isWeekend()) {
                    $now->nextWeekday();
                }
            }
        }
    }
}
