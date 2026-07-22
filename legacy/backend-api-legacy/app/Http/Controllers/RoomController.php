<?php

namespace App\Http\Controllers;

use App\Models\Room;
use App\Models\ShiftAssignment;
use App\Models\Appointment;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;

class RoomController extends Controller
{
    public function index()
    {
        return response()->json(Room::all());
    }

    public function store(Request $request)
    {
        $request->validate([
            'name' => 'required|string|max:255',
        ]);

        $room = Room::create(['name' => $request->name]);
        return response()->json($room, 201);
    }

    public function update(Request $request, $id)
    {
        $request->validate([
            'name' => 'required|string|max:255',
        ]);

        $room = Room::findOrFail($id);
        $room->update(['name' => $request->name]);
        
        return response()->json($room);
    }

    public function destroy($id)
    {
        $room = Room::findOrFail($id);

        // Trava de segurança: verificar se há vínculos
        $hasShifts = ShiftAssignment::where('room_id', $id)->exists();
        $hasAppointments = Appointment::where('room_id', $id)->exists();

        if ($hasShifts || $hasAppointments) {
            return response()->json([
                'message' => 'Esta sala não pode ser excluída pois possui plantões ou consultas vinculados a ela.'
            ], 422);
        }

        $room->delete();
        return response()->json(['message' => 'Sala excluída com sucesso.']);
    }

    public function assign(Request $request)
    {
        $request->validate([
            'room_id' => 'required|exists:rooms,id',
            'shift' => 'required|in:morning,afternoon'
        ]);

        $date = Carbon::today()->toDateString();
        $roomId = $request->room_id;
        $shift = $request->shift;

        // Verificar se a sala já está ocupada por outro médico neste turno
        $existingAssignment = ShiftAssignment::where('room_id', $roomId)
            ->where('date', $date)
            ->where('shift', $shift)
            ->where('doctor_id', '!=', $request->user()->id)
            ->first();

        if ($existingAssignment) {
            return response()->json([
                'message' => 'Esta sala já está ocupada por outro médico neste turno.'
            ], 422);
        }

        // Atualizar ou criar a atribuição para o médico logado
        $assignment = ShiftAssignment::updateOrCreate(
            [
                'doctor_id' => $request->user()->id,
                'date' => $date,
                'shift' => $shift,
            ],
            [
                'room_id' => $roomId
            ]
        );

        return response()->json($assignment);
    }
}
