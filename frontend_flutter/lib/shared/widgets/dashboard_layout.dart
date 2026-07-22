import 'package:flutter/material.dart';
import 'package:frontend_flutter/shared/widgets/sidebar.dart';
import 'package:frontend_flutter/shared/widgets/topbar.dart';

class DashboardLayout extends StatelessWidget {
  final Widget child;

  const DashboardLayout({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: Column(
        children: [
          const Topbar(),
          Expanded(
            child: child,
          ),
        ],
      ),
      endDrawer: const Drawer(child: Sidebar()),
    );
  }
}
