<?php

namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Auth\Events\Registered;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\Hash;
use Illuminate\Validation\Rules;
use Illuminate\Validation\ValidationException;

class RegisteredUserController extends Controller
{
    private function validateCNPJ($cnpj)
    {
        $cnpj = preg_replace('/[^0-9]/', '', (string) $cnpj);
        if (strlen($cnpj) != 14) return false;
        if (preg_match('/(\d)\1{13}/', $cnpj)) return false;

        for ($i = 0, $j = 5, $soma = 0; $i < 12; $i++) {
            $soma += $cnpj[$i] * $j;
            $j = ($j == 2) ? 9 : $j - 1;
        }
        $resto = $soma % 11;
        if ($cnpj[12] != ($resto < 2 ? 0 : 11 - $resto)) return false;

        for ($i = 0, $j = 6, $soma = 0; $i < 13; $i++) {
            $soma += $cnpj[$i] * $j;
            $j = ($j == 2) ? 9 : $j - 1;
        }
        $resto = $soma % 11;
        return $cnpj[13] == ($resto < 2 ? 0 : 11 - $resto);
    }

    public function store(Request $request): Response
    {
        $request->validate([
            'clinic_name' => ['required', 'string', 'max:255'],
            'clinic_cnpj' => ['required', 'string', 'max:20', 'unique:clinics,cnpj'],
            'name' => ['required', 'string', 'max:255'],
            'email' => ['required', 'string', 'lowercase', 'email', 'max:255', 'unique:'.User::class],
            'password' => ['required', 'confirmed', Rules\Password::defaults()],
        ]);

        if (!$this->validateCNPJ($request->clinic_cnpj)) {
            throw ValidationException::withMessages([
                'clinic_cnpj' => ['O CNPJ informado é inválido.'],
            ]);
        }

        $clinic = \App\Models\Clinic::create([
            'name' => $request->clinic_name,
            'cnpj' => preg_replace('/[^0-9]/', '', $request->clinic_cnpj),
            'email' => $request->email,
            'status' => 'trial',
            'trial_ends_at' => now()->addDays(14),
        ]);

        $user = User::create([
            'name' => $request->name,
            'email' => $request->email,
            'password' => Hash::make($request->string('password')),
            'clinic_id' => $clinic->id,
        ]);

        // Create the owner role if it doesn't exist
        \Spatie\Permission\Models\Role::firstOrCreate(['name' => 'owner', 'guard_name' => 'web']);
        setPermissionsTeamId($clinic->id);
        $user->assignRole('owner');

        event(new Registered($user));

        Auth::login($user);

        return response()->noContent();
    }
}
