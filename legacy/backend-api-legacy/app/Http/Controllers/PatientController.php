<?php

namespace App\Http\Controllers;

use App\Models\Patient;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;

class PatientController extends Controller
{
    public function index()
    {
        return response()->json(Patient::all());
    }

    public function store(Request $request)
    {
        if (Auth::user()->role === 'doctor') {
            return response()->json(['message' => 'Médicos não têm permissão para cadastrar novos pacientes.'], 403);
        }

        $request->validate([
            'name' => 'required|string|max:255',
            'cpf' => 'nullable|string|unique:patients,cpf',
            'email' => 'nullable|email',
            'phone' => 'nullable|string',
            'birth_date' => 'nullable|date',
            'address' => 'nullable|string',
            'health_insurance' => 'nullable|string',
        ], [
            'name.required' => 'O nome é obrigatório.',
            'cpf.unique' => 'Este CPF já está cadastrado no sistema.',
            'email.email' => 'O formato do e-mail é inválido.',
        ]);

        $data = $request->only(['name', 'cpf', 'email', 'phone', 'birth_date', 'address', 'health_insurance', 'notes']);
        if (in_array(Auth::user()->role, ['doctor', 'manager'])) {
            $data['medical_history'] = $request->input('medical_history');
        }
        
        $patient = Patient::create($data);

        return response()->json($patient, 201);
    }

    public function show(Patient $patient)
    {
        return response()->json($patient);
    }

    public function update(Request $request, Patient $patient)
    {
        $request->validate([
            'name' => 'sometimes|string|max:255',
            'cpf' => 'nullable|string|unique:patients,cpf,' . $patient->id,
            'email' => 'nullable|email',
            'phone' => 'nullable|string',
            'birth_date' => 'nullable|date',
            'address' => 'nullable|string',
            'health_insurance' => 'nullable|string',
        ], [
            'name.required' => 'O nome é obrigatório.',
            'cpf.required' => 'O CPF é obrigatório.',
            'cpf.unique' => 'Este CPF já está cadastrado no sistema.',
            'email.required' => 'O e-mail é obrigatório.',
            'email.email' => 'O formato do e-mail é inválido.',
            'phone.required' => 'O telefone é obrigatório.'
        ]);

        $user = Auth::user();

        if ($user->role === 'doctor') {
            return response()->json(['message' => 'Médicos não têm permissão para editar os dados gerais do paciente.'], 403);
        }

        // Gerentes podem editar tudo, incluindo medical_history
        if ($user->role === 'manager') {
            $patient->update($request->all());
        } else {
            // Recepcionista não pode editar medical_history
            $patient->update($request->except(['medical_history']));
        }

        return response()->json($patient);
    }

    public function destroy(Request $request, Patient $patient)
    {
        \App\Services\TrashService::checkLimit();

        $user = Auth::user();

        if (!in_array($user->role, ['manager', 'receptionist'])) {
            return response()->json(['message' => 'Você não tem permissão para excluir pacientes.'], 403);
        }

        $request->validate([
            'password' => 'required|string',
        ]);

        if (!\Illuminate\Support\Facades\Hash::check($request->password, $user->password)) {
            return response()->json(['errors' => ['password' => ['Senha incorreta.']]], 422);
        }

        $patient->delete();

        return response()->json(null, 204);
    }
}
