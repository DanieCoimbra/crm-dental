<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Models\Patient;
use App\Models\Appointment;
use Illuminate\Http\Request;
use App\Services\TrashService;

class TrashController extends Controller
{
    public function status()
    {
        return response()->json(TrashService::getStatus());
    }

    public function index()
    {
        $user = auth()->user();
        $users = User::onlyTrashed()->with('deletedBy')->get()->map(function ($item) {
            $item->trash_type = 'user';
            return $item;
        });

        $patients = Patient::onlyTrashed()->with('deletedBy')->get()->map(function ($item) {
            $item->trash_type = 'patient';
            return $item;
        });

        $appointments = Appointment::onlyTrashed()->with(['deletedBy', 'patient', 'doctor'])->get()->map(function ($item) {
            $item->trash_type = 'appointment';
            return $item;
        });

        if ($user->role === 'receptionist') {
            return response()->json([
                'users' => [],
                'patients' => [],
                'appointments' => $appointments
            ]);
        }

        if ($user->role !== 'manager') {
            abort(403, 'Acesso negado.');
        }

        return response()->json([
            'users' => $users,
            'patients' => $patients,
            'appointments' => $appointments
        ]);
    }

    public function restore($type, $id)
    {
        $user = auth()->user();
        if ($user->role === 'receptionist' && $type !== 'appointment') {
            abort(403, 'Acesso negado.');
        }
        if ($user->role !== 'manager' && $user->role !== 'receptionist') {
            abort(403, 'Acesso negado.');
        }

        $model = $this->getModelInstance($type, $id);

        if (!$model) {
            return response()->json(['message' => 'Registro não encontrado na lixeira.'], 404);
        }

        $model->restore();

        return response()->json(['message' => 'Registro restaurado com sucesso.']);
    }

    public function forceDelete(Request $request, $type, $id)
    {
        $user = auth()->user();

        if ($user->role !== 'manager') {
            abort(403, 'Acesso negado. Apenas gerentes podem excluir permanentemente.');
        }

        $request->validate([
            'password' => 'required|string',
        ]);

        if (!\Illuminate\Support\Facades\Hash::check($request->password, $user->password)) {
            return response()->json(['errors' => ['password' => ['Senha incorreta.']]], 422);
        }

        $model = $this->getModelInstance($type, $id);

        if (!$model) {
            return response()->json(['message' => 'Registro não encontrado na lixeira.'], 404);
        }

        $model->forceDelete();

        return response()->json(['message' => 'Registro excluído permanentemente.']);
    }

    private function getModelInstance($type, $id)
    {
        return match ($type) {
            'user' => User::onlyTrashed()->find($id),
            'patient' => Patient::onlyTrashed()->find($id),
            'appointment' => Appointment::onlyTrashed()->find($id),
            default => null,
        };
    }
}
