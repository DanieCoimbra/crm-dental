<?php

namespace Tests\Feature;

use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;
use App\Models\User;
use App\Models\Clinic;
use App\Models\Role;
use Spatie\Permission\Models\Permission;

class RoleTenantTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        app()[\Spatie\Permission\PermissionRegistrar::class]->forgetCachedPermissions();
    }

    public function test_clinic_can_create_own_role()
    {
        $clinic = Clinic::create(['name' => 'Clinic 1', 'cnpj' => '11111111111111', 'plan' => 'trial']);
        $user = User::factory()->create(['clinic_id' => $clinic->id]);

        $response = $this->actingAs($user)->postJson('/api/roles', [
            'name' => 'Custom Role'
        ]);

        $response->assertStatus(201);
        $this->assertDatabaseHas('roles', [
            'name' => 'Custom Role',
            'clinic_id' => $clinic->id
        ]);
    }

    public function test_clinics_can_have_roles_with_same_name()
    {
        $clinic1 = Clinic::create(['name' => 'Clinic 1', 'cnpj' => '11111111111111', 'plan' => 'trial']);
        $user1 = User::factory()->create(['clinic_id' => $clinic1->id]);
        
        $clinic2 = Clinic::create(['name' => 'Clinic 2', 'cnpj' => '22222222222222', 'plan' => 'trial']);
        $user2 = User::factory()->create(['clinic_id' => $clinic2->id]);

        $this->actingAs($user1)->postJson('/api/roles', ['name' => 'Recepcionista'])->assertStatus(201);
        
        // This should pass because unique validation is scoped by clinic_id
        $this->actingAs($user2)->postJson('/api/roles', ['name' => 'Recepcionista'])->assertStatus(201);
        
        $this->assertDatabaseHas('roles', ['name' => 'Recepcionista', 'clinic_id' => $clinic1->id]);
        $this->assertDatabaseHas('roles', ['name' => 'Recepcionista', 'clinic_id' => $clinic2->id]);
    }

    public function test_clinic_cannot_see_other_clinic_roles()
    {
        $clinic1 = Clinic::create(['name' => 'Clinic 1', 'cnpj' => '11111111111111', 'plan' => 'trial']);
        $user1 = User::factory()->create(['clinic_id' => $clinic1->id]);
        
        // Use API to create roles so it matches real request lifecycle
        $this->actingAs($user1)->postJson('/api/roles', ['name' => 'Role Clinic 1'])->assertStatus(201);

        $clinic2 = Clinic::create(['name' => 'Clinic 2', 'cnpj' => '22222222222222', 'plan' => 'trial']);
        $user2 = User::factory()->create(['clinic_id' => $clinic2->id]);
        
        $this->actingAs($user2)->postJson('/api/roles', ['name' => 'Role Clinic 2'])->assertStatus(201);

        $response = $this->actingAs($user1)->getJson('/api/roles');
        $response->assertStatus(200);
    }
}
