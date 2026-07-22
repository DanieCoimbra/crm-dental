<?php

namespace App\Models;

// use Illuminate\Contracts\Auth\MustVerifyEmail;
use Database\Factories\UserFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\Hidden;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\SoftDeletes;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Laravel\Sanctum\HasApiTokens;
use Spatie\Permission\Traits\HasRoles;

#[Fillable(['name', 'email', 'password', 'cpf', 'phone', 'address', 'medical_registry', 'ctps', 'avatar', 'clinic_id'])]
#[Hidden(['password', 'remember_token'])]
class User extends Authenticatable
{
    use HasApiTokens, HasFactory, Notifiable, SoftDeletes, HasRoles, \App\Traits\Tenantable;

    protected $guard_name = 'web';
    protected $appends = ['role', 'permissions_list'];

    public function getRoleAttribute()
    {
        return $this->roles->first()?->name ?? 'receptionist';
    }

    public function getPermissionsListAttribute()
    {
        return $this->getAllPermissions()->pluck('name');
    }

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'email_verified_at' => 'datetime',
            'password' => 'hashed',
        ];
    }

    public function currentRoom()
    {
        return $this->belongsTo(Room::class, 'current_room_id');
    }

    public function shiftAssignments()
    {
        return $this->hasMany(ShiftAssignment::class, 'doctor_id');
    }

    public function clinicalEvolutions()
    {
        return $this->hasMany(ClinicalEvolution::class);
    }

    public function prescriptions()
    {
        return $this->hasMany(Prescription::class);
    }

    public function deletedBy()
    {
        return $this->belongsTo(User::class, 'deleted_by');
    }

    protected static function booted()
    {
        static::deleting(function ($model) {
            if (auth()->check()) {
                $model->deleted_by = auth()->id();
                $model->saveQuietly();
            }
        });

        static::restoring(function ($model) {
            $model->deleted_by = null;
            $model->saveQuietly();
        });
    }
}
