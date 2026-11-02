import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import 'settings_widgets.dart';

/// Device tuning that keeps a root engine alive on aggressive ROMs.
///
/// Every row shows what the device actually reports, read back rather than remembered —
/// a vendor ROM can reset these values on its own. Applying changes the device, so the
/// card starts collapsed behind one deliberate tap, and the accessibility row stays
/// disabled until it is switched on individually.
class EngineTuningSettings extends ConsumerStatefulWidget {
  const EngineTuningSettings({super.key});

  @override
  ConsumerState<EngineTuningSettings> createState() => _EngineTuningSettingsState();
}

class _EngineTuningSettingsState extends ConsumerState<EngineTuningSettings> {
  List<TuningItem>? _items;
  bool _loading = false;
  bool _running = false;
  bool _allowDangerous = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final items = await ref.read(runtimeBridgeProvider).tuningStatus();
      if (mounted) setState(() => _items = items);
    } catch (_) {
      if (mounted) setState(() => _items = const []);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _apply() async {
    if (_running) return;
    setState(() => _running = true);
    try {
      final results = await ref.read(runtimeBridgeProvider).applyTuning(allowDangerous: _allowDangerous);
      final failed = results.where((r) => !r.applied).length;
      if (mounted) {
        showBriefNotice(
          context,
          failed == 0 ? context.l10n.tuningAllApplied : context.l10n.tuningSomeFailed(failed),
        );
      }
      await _load();
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.tuningApplyFailed);
    } finally {
      if (mounted) setState(() => _running = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final items = _items;
    if (items == null || items.isEmpty) {
      // Nothing to offer on this ROM: showing an empty card is worse than showing none.
      return const SizedBox.shrink();
    }
    final pending = items.where((i) => !i.applied && !i.dangerous).length;
    return SettingsSection(
      title: l10n.tuningTitle,
      children: [
        SettingsTile(
          title: pending == 0 ? l10n.tuningNothingToDo : l10n.tuningApply(pending),
          subtitle: pending == 0 ? l10n.tuningAllInPlace : l10n.tuningApplyHint,
          onTap: (_running || _loading || pending == 0) ? null : _apply,
        ),
        ...items.map((item) => _row(item, l10n)),
        if (items.any((i) => i.dangerous))
          SettingsSwitchTile(
            title: l10n.tuningAllowDangerous,
            subtitle: l10n.tuningAllowDangerousHint,
            value: _allowDangerous,
            onChanged: _running ? null : (value) => setState(() => _allowDangerous = value),
          ),
      ],
    );
  }

  Widget _row(TuningItem item, L10n l10n) {
    final state = item.applied ? l10n.tuningStateApplied : l10n.tuningStatePending;
    final persistence = item.persistent ? null : l10n.tuningNotPersistent;
    return SettingsTile(
      title: item.title,
      subtitle: [item.detail, persistence].whereType<String>().join('\n'),
      trailing: Text(
        state,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
          color: item.applied
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
