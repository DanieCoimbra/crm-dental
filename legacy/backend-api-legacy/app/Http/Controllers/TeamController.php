<?php

namespace App\Http\Controllers;

use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;

class TeamController extends Controller
{
    public function index()
    {
        // Retorna todos os usuários com as suas roles
        return response()->json(User::with('roles')->get());
    }

    public function store(Request $request)
    {
        $request->validate([
            'name' => 'required|string|max:255',
            'email' => 'required|string|email|max:255|unique:users',
            'password' => 'required|string|min:8',
            'role' => 'required|string|exists:roles,name',
            'cpf' => 'required|string|unique:users,cpf',
            'phone' => 'nullable|string',
            'address' => 'nullable|string',
            'medical_registry' => 'required_if:role,doctor|nullable|string',
            'ctps' => 'required|string',
        ]);

        if ($request->role === 'owner' && auth()->user()->role !== 'owner') {
            return response()->json(['message' => 'Você não tem permissão para criar um usuário Dono.'], 403);
        }

        $user = \Illuminate\Support\Facades\DB::transaction(function () use ($request) {
            $user = User::create([
                'name' => $request->name,
                'email' => $request->email,
                'password' => Hash::make($request->password),
                'cpf' => $request->cpf,
                'phone' => $request->phone,
                'address' => $request->address,
                'medical_registry' => $request->role === 'doctor' ? $request->medical_registry : null,
                'ctps' => $request->ctps,
            ]);

            $user->assignRole($request->role);

            return $user;
        });

        return response()->json($user, 201);
    }

    public function update(Request $request, User $team)
    {
        $request->validate([
            'name' => 'required|string|max:255',
            'email' => 'required|string|email|max:255|unique:users,email,' . $team->id,
            'role' => 'required|string|exists:roles,name',
            'cpf' => 'required|string|unique:users,cpf,' . $team->id,
            'phone' => 'nullable|string',
            'address' => 'nullable|string',
            'medical_registry' => 'required_if:role,doctor|nullable|string',
            'ctps' => 'required|string',
        ]);

        if ($team->role === 'owner' && auth()->user()->role !== 'owner') {
            return response()->json(['message' => 'Você não tem permissão para editar um usuário Dono.'], 403);
        }

        if ($request->role === 'owner' && auth()->user()->role !== 'owner') {
            return response()->json(['message' => 'Você não tem permissão para promover um usuário a Dono.'], 403);
        }

        $data = $request->except(['password', 'role']);
        if ($request->filled('password')) {
            $data['password'] = Hash::make($request->password);
        }

        if ($request->role !== 'doctor') {
            $data['medical_registry'] = null;
        }

        \Illuminate\Support\Facades\DB::transaction(function () use ($team, $data, $request) {
            $team->update($data);
            $team->syncRoles([$request->role]);
        });

        return response()->json($team);
    }

    public function destroy(Request $request, User $team)
    {
        \App\Services\TrashService::checkLimit();

        $request->validate([
            'password' => 'required|string'
        ]);

        if (!Hash::check($request->password, auth()->user()->password)) {
            return response()->json(['message' => 'Senha incorreta.'], 422);
        }

        if ($team->id === auth()->id()) {
            return response()->json(['message' => 'Você não pode excluir sua própria conta.'], 403);
        }

        if ($team->role === 'owner' && auth()->user()->role !== 'owner') {
            return response()->json(['message' => 'Você não tem permissão para excluir um usuário Dono.'], 403);
        }

        $team->delete();

        return response()->json(['message' => 'Funcionário excluído com sucesso.']);
    }
}
