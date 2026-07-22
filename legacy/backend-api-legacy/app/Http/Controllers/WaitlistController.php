<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use App\Models\Waitlist;

class WaitlistController extends Controller
{
    public function index()
    {
        $waitlists = Waitlist::with(['patient', 'doctor', 'appointmentType'])
            ->orderByRaw("CASE urgency_level WHEN 'high' THEN 1 WHEN 'medium' THEN 2 WHEN 'low' THEN 3 ELSE 4 END")
            ->orderBy('created_at', 'asc')
            ->get();
            
        return response()->json($waitlists);
    }

    public function store(Request $request)
    {
        $request->validate([
            'patient_id' => 'required|exists:patients,id',
            'doctor_id' => 'nullable|exists:users,id',
            'appointment_type_id' => 'nullable|exists:appointment_types,id',
            'preferred_days' => 'nullable|array',
            'preferred_time_range' => 'nullable|string|in:manhã,tarde,qualquer',
            'urgency_level' => 'required|string|in:low,medium,high',
            'notes' => 'nullable|string',
        ]);

        $waitlist = Waitlist::create($request->all());

        return response()->json($waitlist->load(['patient', 'doctor', 'appointmentType']), 201);
    }

    public function show(Waitlist $waitlist)
    {
        return response()->json($waitlist->load(['patient', 'doctor', 'appointmentType']));
    }

    public function update(Request $request, Waitlist $waitlist)
    {
        $request->validate([
            'status' => 'sometimes|string|in:waiting,resolved',
            'preferred_days' => 'nullable|array',
            'preferred_time_range' => 'nullable|string|in:manhã,tarde,qualquer',
            'urgency_level' => 'sometimes|string|in:low,medium,high',
            'notes' => 'nullable|string',
        ]);

        $waitlist->update($request->all());

        return response()->json($waitlist->load(['patient', 'doctor', 'appointmentType']));
    }

    public function destroy(Waitlist $waitlist)
    {
        $waitlist->delete();
        return response()->json(null, 204);
    }

    public function checkMatches(Request $request)
    {
        $request->validate([
            'doctor_id' => 'required|exists:users,id',
            'start_time' => 'required|date',
        ]);

        $startTime = \Carbon\Carbon::parse($request->start_time);
        $dayOfWeek = $startTime->locale('pt_BR')->dayName; // e.g. "segunda-feira"
        $hour = $startTime->hour;
        $timeRange = ($hour < 12) ? 'manhã' : 'tarde';

        // Translate to simpler terms if needed, or just let frontend map them. 
        // For simplicity, let's just do a basic match on doctor and status.
        // And do precise matching in PHP or DB.
        $matches = Waitlist::with(['patient', 'doctor', 'appointmentType'])
            ->where('status', 'waiting')
            ->where(function($q) use ($request) {
                $q->where('doctor_id', $request->doctor_id)
                  ->orWhereNull('doctor_id');
            })
            ->get();

        // Filter in memory for JSON days and time range
        $filtered = $matches->filter(function($w) use ($dayOfWeek, $timeRange) {
            $days = $w->preferred_days ?? [];
            $range = $w->preferred_time_range ?? 'qualquer';

            $dayMatch = empty($days) || collect($days)->map(fn($d) => strtolower($d))->contains(function($val) use ($dayOfWeek) {
                return str_contains(strtolower($dayOfWeek), strtolower($val));
            });

            $timeMatch = ($range === 'qualquer' || strtolower($range) === $timeRange);

            return $dayMatch && $timeMatch;
        });

        // Sort by urgency
        $sorted = $filtered->sortBy(function($w) {
            return match($w->urgency_level) {
                'high' => 1,
                'medium' => 2,
                'low' => 3,
                default => 4,
            };
        })->values();

        return response()->json($sorted);
    }
}
