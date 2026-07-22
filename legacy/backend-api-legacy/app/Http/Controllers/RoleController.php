<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use App\Models\Role;
use Illuminate\Validation\Rule;
use Spatie\Permission\Models\Permission;

class RoleController extends Controller
{
    public function index()
    {
        return response()->json(Role::with('permissions')->get());
    }

    public function permissions()
    {
        return response()->json(Permission::all());
    }

    public function store(Request $request)
    {
        $validated = $request->validate([
            'name' => [
                'required', 
                'string', 
                Rule::unique('roles', 'name')->where('clinic_id', auth()->user()->clinic_id)
            ],
            'permissions' => 'array',
            'permissions.*' => 'exists:permissions,name'
        ]);

        $role = \Illuminate\Support\Facades\DB::transaction(function () use ($validated) {
            $role = Role::create(['name' => $validated['name'], 'guard_name' => 'web']);
            
            if (isset($validated['permissions'])) {
                $role->syncPermissions($validated['permissions']);
            }
            return $role;
        });

        return response()->json($role->load('permissions'), 201);
    }

    public function update(Request $request, Role $role)
    {
        if (in_array($role->name, ['manager'])) {
            return response()->json(['message' => 'Cannot modify core roles'], 403);
        }

        $validated = $request->validate([
            'name' => [
                'required', 
                'string', 
                Rule::unique('roles', 'name')->ignore($role->id)->where('clinic_id', auth()->user()->clinic_id)
            ],
            'permissions' => 'array',
            'permissions.*' => 'exists:permissions,name'
        ]);

        \Illuminate\Support\Facades\DB::transaction(function () use ($role, $validated) {
            $role->update(['name' => $validated['name']]);

            if (isset($validated['permissions'])) {
                $role->syncPermissions($validated['permissions']);
            }
        });

        return response()->json($role->load('permissions'));
    }

    public function destroy(Role $role)
    {
        if (in_array($role->name, ['manager', 'doctor', 'receptionist'])) {
            return response()->json(['message' => 'Cannot delete core roles'], 403);
        }

        $role->delete();
        return response()->json(null, 204);
    }
}
