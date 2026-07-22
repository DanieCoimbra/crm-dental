<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class ShiftAssignment extends Model
{
    protected $fillable = ['doctor_id', 'room_id', 'date', 'shift'];

    public function doctor() { return $this->belongsTo(User::class, 'doctor_id'); }
    public function room() { return $this->belongsTo(Room::class); }
}
