<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use App\Models\AppointmentType;

class AppointmentTypeController extends Controller
{
    public function index()
    {
        return response()->json(AppointmentType::all());
    }

    public function store(Request $request)
    {
        if (request()->user()->role !== 'manager') {
            return response()->json(['message' => 'Unauthorized'], 403);
        }

        $validated = $request->validate([
            'name' => 'required|string|max:255',
            'duration_minutes' => 'required|integer|min:1',
            'color' => 'nullable|string|max:20'
        ]);

        $type = AppointmentType::create($validated);
        return response()->json($type, 201);
    }

    public function show(AppointmentType $appointmentType)
    {
        return response()->json($appointmentType);
    }

    public function update(Request $request, AppointmentType $appointmentType)
    {
        if (request()->user()->role !== 'manager') {
            return response()->json(['message' => 'Unauthorized'], 403);
        }

        $validated = $request->validate([
            'name' => 'sometimes|string|max:255',
            'duration_minutes' => 'sometimes|integer|min:1',
            'color' => 'nullable|string|max:20'
        ]);

        $appointmentType->update($validated);
        return response()->json($appointmentType);
    }

    public function destroy(AppointmentType $appointmentType)
    {
        if (request()->user()->role !== 'manager') {
            return response()->json(['message' => 'Unauthorized'], 403);
        }

        if ($appointmentType->appointments()->count() > 0) {
            return response()->json(['message' => 'Não é possível excluir este tipo pois existem consultas vinculadas a ele.'], 400);
        }

        $appointmentType->delete();
        return response()->json(['message' => 'Tipo de atendimento excluído.']);
    }
}
