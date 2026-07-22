<?php

namespace App\Http\Controllers;

use App\Models\Prescription;
use Illuminate\Http\Request;

class PrescriptionController extends Controller
{
    public function index($patientId)
    {
        $prescriptions = Prescription::with('user:id,name,role')->where('patient_id', $patientId)->orderBy('created_at', 'desc')->get();
        return response()->json($prescriptions);
    }

    public function store(Request $request, $patientId)
    {
        $request->validate([
            'type' => 'required|in:receita,atestado',
            'title' => 'nullable|string|max:255',
            'content' => 'required|string',
        ]);

        $prescription = Prescription::create([
            'patient_id' => $patientId,
            'user_id' => $request->user()->id,
            'type' => $request->type,
            'title' => $request->title,
            'content' => $request->content,
        ]);

        return response()->json($prescription->load('user:id,name,role'), 201);
    }

    public function destroy($id)
    {
        $prescription = Prescription::findOrFail($id);
        
        if (auth()->user()->role !== 'manager' && auth()->id() !== $prescription->user_id) {
            return response()->json(['message' => 'Acesso negado.'], 403);
        }

        $prescription->delete();
        return response()->json(['message' => 'Prescrição excluída.']);
    }
}
