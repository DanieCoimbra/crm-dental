import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:frontend_flutter/features/auth/data/auth_repository.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';

class RoomSelector extends ConsumerStatefulWidget {
  const RoomSelector({super.key});

  @override
  ConsumerState<RoomSelector> createState() => _RoomSelectorState();
}

class _RoomSelectorState extends ConsumerState<RoomSelector> {
  bool _isLoading = false;

  Future<void> _updateRoom(int? roomId) async {
    setState(() => _isLoading = true);
    try {
      final repo = ref.read(authRepositoryProvider);
      await repo.updateRoom(roomId);
      ref.invalidate(currentUserProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Sala atualizada')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao atualizar sala: $e')));
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final userAsync = ref.watch(currentUserProvider);
    final roomsAsync = ref.watch(roomsProvider);

    return userAsync.when(
      loading: () => const SizedBox(width: 100, child: LinearProgressIndicator()),
      error: (_, _) => const SizedBox.shrink(),
      data: (user) {
        return roomsAsync.when(
          loading: () => const SizedBox.shrink(),
          error: (_, _) => const SizedBox.shrink(),
          data: (rooms) {
            final activeRooms = rooms.where((r) => r.isActive).toList();
            if (activeRooms.isEmpty) return const SizedBox.shrink();

            return _isLoading
                ? const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2))
                : Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                    decoration: BoxDecoration(
                      color: Colors.blue.shade50,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: Colors.blue.shade200),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<int?>(
                        value: user.currentRoomId,
                        hint: const Text('Selecione a Sala', style: TextStyle(fontSize: 12)),
                        isDense: true,
                        icon: const Icon(Icons.meeting_room, size: 16),
                        items: [
                          const DropdownMenuItem<int?>(
                            value: null,
                            child: Text('Nenhuma', style: TextStyle(fontSize: 12)),
                          ),
                          ...activeRooms.map((room) => DropdownMenuItem<int?>(
                                value: room.id,
                                child: Text(room.name, style: const TextStyle(fontSize: 12)),
                              )),
                        ],
                        onChanged: (val) {
                          if (val != user.currentRoomId) {
                            _updateRoom(val);
                          }
                        },
                      ),
                    ),
                  );
          },
        );
      },
    );
  }
}
