<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;

class ProfileController extends Controller
{
    public function updateAvatar(Request $request)
    {
        $request->validate([
            'avatar' => 'required|image|max:15360', // Permite até 15MB
        ]);

        $user = $request->user();

        if ($user->avatar) {
            // Remove old avatar if exists
            $oldAvatarPath = str_replace('/storage/', '', $user->avatar);
            Storage::disk('public')->delete($oldAvatarPath);
        }

        $path = $request->file('avatar')->store('avatars', 'public');
        $user->avatar = '/storage/' . $path;
        $user->save();

        return response()->json(['avatar' => $user->avatar]);
    }

    public function updateProfile(Request $request)
    {
        $request->validate([
            'phone' => 'nullable|string',
            'address' => 'nullable|string',
        ]);

        $user = $request->user();
        $user->phone = $request->phone;
        $user->address = $request->address;
        $user->save();

        return response()->json($user);
    }

    public function setCurrentRoom(Request $request)
    {
        $request->validate([
            'room_id' => 'nullable|exists:rooms,id',
        ]);

        $user = $request->user();

        if ($request->room_id) {
            $occupied = \App\Models\User::where('current_room_id', $request->room_id)
                ->where('id', '!=', $user->id)
                ->first();
                
            if ($occupied) {
                return response()->json(['message' => 'Esta sala já está ocupada por outro médico.'], 409);
            }
        }

        $user->current_room_id = $request->room_id;
        $user->save();

        return response()->json($user);
    }
}
