<?php

namespace App\Services;

use App\Models\Waitlist;
use Carbon\Carbon;
use Illuminate\Support\Facades\DB;

class SmartBookingService
{
    /**
     * Find suggestions from the waitlist for a newly created gap.
     *
     * @param int $doctorId
     * @param Carbon $startTime
     * @param Carbon $endTime
     * @return \Illuminate\Database\Eloquent\Collection
     */
    public function findSuggestionsForGap($doctorId, $startTime, $endTime)
    {
        $gapMinutes = $startTime->diffInMinutes($endTime);

        // Se o buraco for menor que 15 minutos, ignoramos (muito pequeno para encaixe prático)
        if ($gapMinutes < 15) {
            return collect();
        }

        $suggestions = Waitlist::with(['patient', 'appointmentType'])
            ->where('status', 'waiting')
            ->where(function ($query) use ($doctorId) {
                $query->where('doctor_id', $doctorId)
                      ->orWhereNull('doctor_id');
            })
            ->whereHas('appointmentType', function ($query) use ($gapMinutes) {
                $query->where('duration_minutes', '<=', $gapMinutes);
            })
            // Custom ordering: high (1), medium (2), low (3)
            ->orderByRaw("
                CASE urgency_level
                    WHEN 'high' THEN 1
                    WHEN 'medium' THEN 2
                    WHEN 'low' THEN 3
                    ELSE 4
                END
            ")
            ->orderBy('created_at', 'asc')
            ->limit(3)
            ->get();

        return $suggestions;
    }
}
