import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import 'settings_widgets.dart';

/// Root chroot launch: the engine runs in the real filesystem instead of through
/// PRoot. Root only mounts and drops to this app's own uid (see `RootLaunch.kt`),
/// so the switch is about speed, not about giving the engine root.
class RootEngineSettings extends ConsumerStatefulWidget {
  const RootEngineSettings({super.key});

  @override
  ConsumerState<RootEngineSettings> createState() => _RootEngineSettingsState();
}

class _RootEngineSettingsState extends ConsumerState<RootEngineSettings> {
  bool _saving = false;

  @override
  Widget build(BuildContext context) {
    final runtime = ref.watch(localRuntimeProvider);
    final status = runtime.status;
    final l10n = context.l10n;
    final stopped = !status.phase.isBusy && status.phase != RuntimePhase.running;
    // Mirrors Direct: the switch is inert while the engine is up, since the
    // execution world is fixed when the process spawns.
    final rootKnown = status.rootEngineSupported || status.rootEngineAvailable || status.rootEngineEnabled;
    final enabled = runtime.loaded && stopped && !_saving && rootKnown;
    final fallback = status.rootEngineFallbackReason;
    final rootFailed = fallback == 'rootFailed';
    final hint = !rootKnown
        ? l10n.phoneRootUnavailableHint
        : rootFailed && !status.rootEngineEnabled
        ? l10n.phoneRootFailedHint
        : !stopped
        ? l10n.phoneDirectStopFirst
        : status.inSafeMode
        ? l10n.phoneRootSafeHint
        : l10n.phoneRootHint;

    return SettingsSection(
      title: l10n.phoneDeveloperOptions,
      children: [
        // Only after a chroot launch failed: the switch already reads off, this is
        // the deliberate way back to PRoot without waiting for a runtime change.
        if (status.rootEngineEnabled && status.rootEngineActive && status.launchFailureReason != null)
          SettingsTile(
            title: l10n.phoneReturnCompatible,
            subtitle: stopped ? null : l10n.phoneDirectStopFirst,
            onTap: enabled ? () => _change(false) : null,
          ),
        SettingsSwitchTile(
          title: l10n.phoneRootEngine,
          subtitle: hint,
          value: status.rootEngineEnabled,
          onChanged: enabled ? _change : null,
        ),
      ],
    );
  }

  Future<void> _change(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setRootEngine(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneRootChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}
