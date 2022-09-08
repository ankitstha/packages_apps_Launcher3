package com.android.launcher3.popup;

import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_PRIVATE_SPACE_INSTALL_SYSTEM_SHORTCUT_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_PRIVATE_SPACE_UNINSTALL_SYSTEM_SHORTCUT_TAP;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;

import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_APP_INFO_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_DONT_SUGGEST_APP_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_WIDGETS_TAP;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.SuspendDialogInfo;
import android.graphics.Rect;
import android.os.Process;
import android.os.UserHandle;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Flags;
import com.android.launcher3.R;
import com.android.launcher3.SecondaryDropTarget;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.PrivateProfileManager;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.uioverrides.ApiWrapper;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.InstantAppResolver;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.WidgetsBottomSheet;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a system shortcut for a given app. The shortcut should have a label and icon, and an
 * onClickListener that depends on the item that the shortcut services.
 *
 * Example system shortcuts, defined as inner classes, include Widgets and AppInfo.
 *
 * @param <T> extends {@link ActivityContext}
 */
public abstract class SystemShortcut<T extends ActivityContext> extends ItemInfo
        implements View.OnClickListener {

    private final int mIconResId;
    protected final int mLabelResId;
    protected int mAccessibilityActionId;

    protected final T mTarget;
    protected final ItemInfo mItemInfo;
    protected final View mOriginalView;

    public SystemShortcut(int iconResId, int labelResId, T target, ItemInfo itemInfo,
            View originalView) {
        mIconResId = iconResId;
        mLabelResId = labelResId;
        mAccessibilityActionId = labelResId;
        mTarget = target;
        mItemInfo = itemInfo;
        mOriginalView = originalView;
    }

    public SystemShortcut(SystemShortcut<T> other) {
        mIconResId = other.mIconResId;
        mLabelResId = other.mLabelResId;
        mAccessibilityActionId = other.mAccessibilityActionId;
        mTarget = other.mTarget;
        mItemInfo = other.mItemInfo;
        mOriginalView = other.mOriginalView;
    }

    public void setIconAndLabelFor(View iconView, TextView labelView) {
        iconView.setBackgroundResource(mIconResId);
        labelView.setText(mLabelResId);
    }

    public void setIconAndContentDescriptionFor(ImageView view) {
        view.setImageResource(mIconResId);
        view.setContentDescription(view.getContext().getText(mLabelResId));
    }

    public AccessibilityNodeInfo.AccessibilityAction createAccessibilityAction(Context context) {
        return new AccessibilityNodeInfo.AccessibilityAction(
                mAccessibilityActionId, context.getText(mLabelResId));
    }

    public boolean hasHandlerForAction(int action) {
        return mAccessibilityActionId == action;
    }

    public interface Factory<T extends ActivityContext> {

        @Nullable
        SystemShortcut<T> getShortcut(T context, ItemInfo itemInfo, @NonNull View originalView);
    }

    public static final Factory<ActivityContext> WIDGETS = (context, itemInfo, originalView) -> {
        if (itemInfo.getTargetComponent() == null) return null;
        final List<WidgetItem> widgets =
                context.getPopupDataProvider().getWidgetsForPackageUser(new PackageUserKey(
                        itemInfo.getTargetComponent().getPackageName(), itemInfo.user));
        if (widgets.isEmpty()) {
            return null;
        }
        return new Widgets(context, itemInfo, originalView);
    };

    public static class Widgets<T extends ActivityContext> extends SystemShortcut<T> {
        public Widgets(T target, ItemInfo itemInfo, @NonNull View originalView) {
            super(R.drawable.ic_widget, R.string.widget_button_text, target, itemInfo,
                    originalView);
        }

        @Override
        public void onClick(View view) {
            if (!Utilities.isWorkspaceEditAllowed(mTarget.getApplicationContext())) return;
            AbstractFloatingView.closeAllOpenViews(mTarget);
            WidgetsBottomSheet widgetsBottomSheet =
                    (WidgetsBottomSheet) mTarget.getLayoutInflater().inflate(
                            R.layout.widgets_bottom_sheet, mTarget.getDragLayer(), false);
            widgetsBottomSheet.populateAndShow(mItemInfo);
            mTarget.getStatsLogManager().logger().withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_WIDGETS_TAP);
        }
    }

    public static final Factory<ActivityContext> APP_INFO = AppInfo::new;

    public static class AppInfo<T extends ActivityContext> extends SystemShortcut<T> {

        @Nullable
        private SplitAccessibilityInfo mSplitA11yInfo;

        public AppInfo(T target, ItemInfo itemInfo, @NonNull View originalView) {
            super(R.drawable.ic_info_no_shadow, R.string.app_info_drop_target_label, target,
                    itemInfo, originalView);
        }

        /**
         * Constructor used by overview for staged split to provide custom A11y information.
         *
         * Future improvements considerations:
         * Have the logic in {@link #createAccessibilityAction(Context)} be moved to super
         * call in {@link SystemShortcut#createAccessibilityAction(Context)} by having
         * SystemShortcut be aware of TaskContainers and staged split.
         * That way it could directly create the correct node info for any shortcut that supports
         * split, but then we'll need custom resIDs for each pair of shortcuts.
         */
        public AppInfo(T target, ItemInfo itemInfo, View originalView,
                SplitAccessibilityInfo accessibilityInfo) {
            this(target, itemInfo, originalView);
            mSplitA11yInfo = accessibilityInfo;
            mAccessibilityActionId = accessibilityInfo.nodeId;
        }

        @Override
        public AccessibilityNodeInfo.AccessibilityAction createAccessibilityAction(
                Context context) {
            if (mSplitA11yInfo != null && mSplitA11yInfo.containsMultipleTasks) {
                String accessibilityLabel = context.getString(R.string.split_app_info_accessibility,
                        mSplitA11yInfo.taskTitle);
                return new AccessibilityNodeInfo.AccessibilityAction(mAccessibilityActionId,
                        accessibilityLabel);
            } else {
                return super.createAccessibilityAction(context);
            }
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView(mTarget);
            Rect sourceBounds = Utilities.getViewBounds(view);
            new PackageManagerHelper(view.getContext()).startDetailsActivityForInfo(
                    mItemInfo, sourceBounds, ActivityOptions.makeBasic().toBundle());
            mTarget.getStatsLogManager().logger().withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_APP_INFO_TAP);
        }

        public static class SplitAccessibilityInfo {
            public final boolean containsMultipleTasks;
            public final CharSequence taskTitle;
            public final int nodeId;

            public SplitAccessibilityInfo(boolean containsMultipleTasks,
                    CharSequence taskTitle, int nodeId) {
                this.containsMultipleTasks = containsMultipleTasks;
                this.taskTitle = taskTitle;
                this.nodeId = nodeId;
            }
        }
    }

    public static final Factory<ActivityContext> PRIVATE_PROFILE_INSTALL =
            (context, itemInfo, originalView) -> {
                if (originalView == null) {
                    return null;
                }
                if (itemInfo.getTargetComponent() == null
                        || !(itemInfo instanceof com.android.launcher3.model.data.AppInfo)
                        || !itemInfo.getContainerInfo().hasAllAppsContainer()
                        || !Process.myUserHandle().equals(itemInfo.user)) {
                    return null;
                }

                PrivateProfileManager privateProfileManager =
                        context.getAppsView().getPrivateProfileManager();
                if (privateProfileManager == null || !privateProfileManager.isEnabled()) {
                    return null;
                }

                UserHandle privateProfileUser = privateProfileManager.getProfileUser();
                if (privateProfileUser == null) {
                    return null;
                }
                // Do not show shortcut if an app is already installed to the space
                ComponentName targetComponent = itemInfo.getTargetComponent();
                if (context.getAppsView().getAppsStore().getApp(
                        new ComponentKey(targetComponent, privateProfileUser)) != null) {
                    return null;
                }

                // Do not show shortcut for settings
                String[] packagesToSkip =
                        originalView.getContext().getResources()
                                .getStringArray(R.array.skip_private_profile_shortcut_packages);
                if (Arrays.asList(packagesToSkip).contains(targetComponent.getPackageName())) {
                    return null;
                }

                return new InstallToPrivateProfile<>(
                        context, itemInfo, originalView, privateProfileUser);
            };

    static class InstallToPrivateProfile<T extends ActivityContext> extends SystemShortcut<T> {
        UserHandle mSpaceUser;

        InstallToPrivateProfile(T target, ItemInfo itemInfo, @NonNull View originalView,
                UserHandle spaceUser) {
            // TODO(b/302666597): update icon once available
            super(
                    R.drawable.ic_install_to_private,
                    R.string.install_private_system_shortcut_label,
                    target,
                    itemInfo,
                    originalView);
            mSpaceUser = spaceUser;
        }

        @Override
        public void onClick(View view) {
            Intent intent =
                    ApiWrapper.getAppMarketActivityIntent(
                            view.getContext(),
                            mItemInfo.getTargetComponent().getPackageName(),
                            mSpaceUser);
            mTarget.startActivitySafely(view, intent, mItemInfo);
            AbstractFloatingView.closeAllOpenViews(mTarget);
            mTarget.getStatsLogManager()
                    .logger()
                    .withItemInfo(mItemInfo)
                    .log(LAUNCHER_PRIVATE_SPACE_INSTALL_SYSTEM_SHORTCUT_TAP);
        }
    }

    public static final Factory<ActivityContext> INSTALL =
            (activity, itemInfo, originalView) -> {
                if (originalView == null) {
                    return null;
                }
                boolean supportsWebUI = (itemInfo instanceof WorkspaceItemInfo)
                        && ((WorkspaceItemInfo) itemInfo).hasStatusFlag(
                        WorkspaceItemInfo.FLAG_SUPPORTS_WEB_UI);
                boolean isInstantApp = false;
                if (itemInfo instanceof com.android.launcher3.model.data.AppInfo) {
                    com.android.launcher3.model.data.AppInfo
                            appInfo = (com.android.launcher3.model.data.AppInfo) itemInfo;
                    isInstantApp = InstantAppResolver.newInstance(
                            originalView.getContext()).isInstantApp(appInfo);
                }
                boolean enabled = supportsWebUI || isInstantApp;
                if (!enabled) {
                    return null;
                }
                return new Install(activity, itemInfo, originalView);
            };

    public static class Install<T extends ActivityContext> extends SystemShortcut<T> {

        public Install(T target, ItemInfo itemInfo, @NonNull View originalView) {
            super(R.drawable.ic_install_no_shadow, R.string.install_drop_target_label,
                    target, itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            Intent intent = ApiWrapper.getAppMarketActivityIntent(view.getContext(),
                    mItemInfo.getTargetComponent().getPackageName(),
                    Process.myUserHandle());
            mTarget.startActivitySafely(view, intent, mItemInfo);
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    public static final Factory<ActivityContext> DONT_SUGGEST_APP =
            (activity, itemInfo, originalView) -> {
                if (!itemInfo.isPredictedItem()) {
                    return null;
                }
                return new DontSuggestApp<>(activity, itemInfo, originalView);
            };

    private static class DontSuggestApp<T extends ActivityContext> extends SystemShortcut<T> {
        DontSuggestApp(T target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_block_no_shadow, R.string.dismiss_prediction_label, target,
                    itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView(mTarget);
            mTarget.getStatsLogManager().logger()
                    .withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_DONT_SUGGEST_APP_TAP);
        }
    }

    public static final Factory<ActivityContext> UNINSTALL_APP =
            (activityContext, itemInfo, originalView) -> {
                if (originalView == null) {
                    return null;
                }
                if (!Flags.enablePrivateSpace()) {
                    return null;
                }
                if (!UserCache.INSTANCE.get(originalView.getContext()).getUserInfo(
                        itemInfo.user).isPrivate()) {
                    // If app is not Private Space app.
                    return null;
                }
                ComponentName cn = SecondaryDropTarget.getUninstallTarget(originalView.getContext(),
                        itemInfo);
                if (cn == null) {
                    // If component name is null, don't show uninstall shortcut.
                    // System apps will have component name as null.
                    return null;
                }
                return new UninstallApp(activityContext, itemInfo, originalView, cn);
            };

    private static class UninstallApp<T extends ActivityContext> extends SystemShortcut<T> {
        @NonNull ComponentName mComponentName;

        UninstallApp(T target, ItemInfo itemInfo, @NonNull View originalView,
                @NonNull ComponentName cn) {
            super(R.drawable.ic_uninstall_no_shadow, R.string.uninstall_drop_target_label, target,
                    itemInfo, originalView);
            mComponentName = cn;

        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView(mTarget);
            SecondaryDropTarget.performUninstall(view.getContext(), mComponentName, mItemInfo);
            mTarget.getStatsLogManager()
                    .logger()
                    .withItemInfo(mItemInfo)
                    .log(LAUNCHER_PRIVATE_SPACE_UNINSTALL_SYSTEM_SHORTCUT_TAP);
        }
    }


    public static final Factory<BaseDraggingActivity> PAUSE_APPS =
            (activity, itemInfo, originalView) -> {
                if (new PackageManagerHelper(activity).isAppSuspended(
                        itemInfo.getTargetComponent().getPackageName(), itemInfo.user)) {
                    return null;
                }
                return new PauseApps(activity, itemInfo, originalView);
    };

    public static class PauseApps<T extends Context & ActivityContext> extends SystemShortcut<T> {

        public PauseApps(T target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_hourglass, R.string.paused_apps_drop_target_label, target,
                    itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            Context context = view.getContext();
            CharSequence appLabel = context.getPackageManager().getApplicationLabel(
                    new PackageManagerHelper(context).getApplicationInfo(
                            mItemInfo.getTargetComponent().getPackageName(), mItemInfo.user, 0));
            new AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.pause_apps_dialog_title,
                            appLabel))
                    .setMessage(context.getString(R.string.pause_apps_dialog_message,
                            appLabel))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pause, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                                        new String[]{
                                                mItemInfo.getTargetComponent().getPackageName()},
                                        true, null, null,
                                        new SuspendDialogInfo.Builder()
                                                .setTitle(R.string.paused_apps_dialog_title)
                                                .setMessage(R.string.paused_apps_dialog_message)
                                                .setNeutralButtonAction(BUTTON_ACTION_UNSUSPEND)
                                                .build(), 0, context.getOpPackageName(),
                                        context.getUserId(),
                                        mItemInfo.user.getIdentifier());
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to pause app", e);
                            }
                        }
                    })
                    .show();
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    public static <T extends ActivityContext> void dismissTaskMenuView(T activity) {
        AbstractFloatingView.closeOpenViews(activity, true,
                AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_REBIND_SAFE);
    }
}
