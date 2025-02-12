/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.compat;

import static android.app.compat.PackageOverride.VALUE_DISABLED;
import static android.app.compat.PackageOverride.VALUE_ENABLED;
import static android.app.compat.PackageOverride.VALUE_UNDEFINED;

import android.annotation.Nullable;
import android.app.compat.PackageOverride;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.content.pm.ApplicationInfo;

import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.OverrideAllowedState;
import com.android.server.compat.config.Change;
import com.android.server.compat.overrides.ChangeOverrides;
import com.android.server.compat.overrides.OverrideValue;
import com.android.server.compat.overrides.RawOverrideValue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the state of a single compatibility change.
 *
 * <p>A compatibility change has a default setting, determined by the {@code enableAfterTargetSdk}
 * and {@code disabled} constructor parameters. If a change is {@code disabled}, this overrides any
 * target SDK criteria set. These settings can be overridden for a specific package using
 * {@link #addPackageOverrideInternal(String, boolean)}.
 *
 * <p>Note, this class is not thread safe so callers must ensure thread safety.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class CompatChange extends CompatibilityChangeInfo {

    /**
     * A change ID to be used only in the CTS test for this SystemApi
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = 31) // Needs to be > test APK targetSdkVersion.
    static final long CTS_SYSTEM_API_CHANGEID = 149391281; // This is a bug id.

    /**
     * An overridable change ID to be used only in the CTS test for this SystemApi
     */
    @ChangeId
    @Disabled
    @Overridable
    static final long CTS_SYSTEM_API_OVERRIDABLE_CHANGEID = 174043039; // This is a bug id.


    /**
     * Callback listener for when compat changes are updated for a package.
     * See {@link #registerListener(ChangeListener)} for more details.
     */
    public interface ChangeListener {
        /**
         * Called upon an override change for packageName and the change this listener is
         * registered for. Called before the app is killed.
         */
        void onCompatChange(String packageName);
    }

    ChangeListener mListener = null;

    private ConcurrentHashMap<String, Boolean> mEvaluatedOverrides;
    private ConcurrentHashMap<String, PackageOverride> mRawOverrides;

    public CompatChange(long changeId) {
        this(changeId, null, -1, -1, false, false, null, false);
    }

    /**
     * @param change an object generated by services/core/xsd/platform-compat-config.xsd
     */
    public CompatChange(Change change) {
        this(change.getId(), change.getName(), change.getEnableAfterTargetSdk(),
                change.getEnableSinceTargetSdk(), change.getDisabled(), change.getLoggingOnly(),
                change.getDescription(), change.getOverridable());
    }

    /**
     * @param changeId Unique ID for the change. See {@link android.compat.Compatibility}.
     * @param name Short descriptive name.
     * @param enableAfterTargetSdk {@code targetSdkVersion} restriction. See {@link EnabledAfter};
     *                             -1 if the change is always enabled.
     * @param enableSinceTargetSdk {@code targetSdkVersion} restriction. See {@link EnabledSince};
     *                             -1 if the change is always enabled.
     * @param disabled If {@code true}, overrides any {@code enableAfterTargetSdk} set.
     */
    public CompatChange(long changeId, @Nullable String name, int enableAfterTargetSdk,
            int enableSinceTargetSdk, boolean disabled, boolean loggingOnly, String description,
            boolean overridable) {
        super(changeId, name, enableAfterTargetSdk, enableSinceTargetSdk, disabled, loggingOnly,
              description, overridable);

        // Initialize override maps.
        mEvaluatedOverrides = new ConcurrentHashMap<>();
        mRawOverrides = new ConcurrentHashMap<>();
    }

    synchronized void registerListener(ChangeListener listener) {
        if (mListener != null) {
            throw new IllegalStateException(
                    "Listener for change " + toString() + " already registered.");
        }
        mListener = listener;
    }


    /**
     * Force the enabled state of this change for a given package name. The change will only take
     * effect after that packages process is killed and restarted.
     *
     * @param pname Package name to enable the change for.
     * @param enabled Whether or not to enable the change.
     */
    private void addPackageOverrideInternal(String pname, boolean enabled) {
        if (getLoggingOnly()) {
            throw new IllegalArgumentException(
                    "Can't add overrides for a logging only change " + toString());
        }
        mEvaluatedOverrides.put(pname, enabled);
        notifyListener(pname);
    }

    private void removePackageOverrideInternal(String pname) {
        if (mEvaluatedOverrides.remove(pname) != null) {
            notifyListener(pname);
        }
    }

    /**
     * Tentatively set the state of this change for a given package name.
     * The override will only take effect after that package is installed, if applicable.
     *
     * @param packageName Package name to tentatively enable the change for.
     * @param override The package override to be set
     * @param allowedState Whether the override is allowed.
     * @param versionCode The version code of the package.
     */
    synchronized void addPackageOverride(String packageName, PackageOverride override,
            OverrideAllowedState allowedState, @Nullable Long versionCode) {
        if (getLoggingOnly()) {
            throw new IllegalArgumentException(
                    "Can't add overrides for a logging only change " + toString());
        }
        mRawOverrides.put(packageName, override);
        recheckOverride(packageName, allowedState, versionCode);
    }

    /**
     * Rechecks an existing (and possibly deferred) override.
     *
     * <p>For deferred overrides, check if they can be promoted to a regular override. For regular
     * overrides, check if they need to be demoted to deferred.</p>
     *
     * @param packageName Package name to apply deferred overrides for.
     * @param allowedState Whether the override is allowed.
     * @param versionCode The version code of the package.
     *
     * @return {@code true} if the recheck yielded a result that requires invalidating caches
     *         (a deferred override was consolidated or a regular override was removed).
     */
    synchronized boolean recheckOverride(String packageName, OverrideAllowedState allowedState,
            @Nullable Long versionCode) {
        if (packageName == null) {
            return false;
        }
        boolean allowed = (allowedState.state == OverrideAllowedState.ALLOWED);
        // If the app is not installed or no longer has raw overrides, evaluate to false
        if (versionCode == null || !mRawOverrides.containsKey(packageName) || !allowed) {
            removePackageOverrideInternal(packageName);
            return false;
        }
        // Evaluate the override based on its version
        int overrideValue = mRawOverrides.get(packageName).evaluate(versionCode);
        switch (overrideValue) {
            case VALUE_UNDEFINED:
                removePackageOverrideInternal(packageName);
                break;
            case VALUE_ENABLED:
                addPackageOverrideInternal(packageName, true);
                break;
            case VALUE_DISABLED:
                addPackageOverrideInternal(packageName, false);
                break;
        }
        return true;
    }

    /**
     * Remove any package override for the given package name, restoring the default behaviour.
     *
     * <p>Note, this method is not thread safe so callers must ensure thread safety.
     *
     * @param pname Package name to reset to defaults for.
     * @param allowedState Whether the override is allowed.
     * @param versionCode The version code of the package.
     */
    synchronized boolean removePackageOverride(String pname, OverrideAllowedState allowedState,
            @Nullable Long versionCode) {
        if (mRawOverrides.containsKey(pname)) {
            allowedState.enforce(getId(), pname);
            mRawOverrides.remove(pname);
            recheckOverride(pname, allowedState, versionCode);
            return true;
        }
        return false;
    }

    /**
     * Find if this change is enabled for the given package, taking into account any overrides that
     * exist.
     *
     * @param app Info about the app in question
     * @return {@code true} if the change should be enabled for the package.
     */
    boolean isEnabled(ApplicationInfo app, AndroidBuildClassifier buildClassifier) {
        if (app == null) {
            return defaultValue();
        }
        if (app.packageName != null) {
            final Boolean enabled = mEvaluatedOverrides.get(app.packageName);
            if (enabled != null) {
                return enabled;
            }
        }
        if (getDisabled()) {
            return false;
        }
        if (getEnableSinceTargetSdk() != -1) {
            // If the change is gated by a platform version newer than the one currently installed
            // on the device, disregard the app's target sdk version.
            int compareSdk = Math.min(app.targetSdkVersion, buildClassifier.platformTargetSdk());
            return compareSdk >= getEnableSinceTargetSdk();
        }
        return true;
    }

    /**
     * Find if this change will be enabled for the given package after installation.
     *
     * @param packageName The package name in question
     * @return {@code true} if the change should be enabled for the package.
     */
    boolean willBeEnabled(String packageName) {
        if (packageName == null) {
            return defaultValue();
        }
        final PackageOverride override = mRawOverrides.get(packageName);
        if (override != null) {
            switch (override.evaluateForAllVersions()) {
                case VALUE_ENABLED:
                    return true;
                case VALUE_DISABLED:
                    return false;
                case VALUE_UNDEFINED:
                    return defaultValue();
            }
        }
        return defaultValue();
    }

    /**
     * Returns the default value for the change id, assuming there are no overrides.
     *
     * @return {@code false} if it's a default disabled change, {@code true} otherwise.
     */
    boolean defaultValue() {
        return !getDisabled();
    }

    synchronized void clearOverrides() {
        mRawOverrides.clear();
        mEvaluatedOverrides.clear();
    }

    synchronized void loadOverrides(ChangeOverrides changeOverrides) {
        // Load deferred overrides for backwards compatibility
        if (changeOverrides.getDeferred() != null) {
            for (OverrideValue override : changeOverrides.getDeferred().getOverrideValue()) {
                mRawOverrides.put(override.getPackageName(),
                        new PackageOverride.Builder().setEnabled(
                                override.getEnabled()).build());
            }
        }

        // Load validated overrides. For backwards compatibility, we also add them to raw overrides.
        if (changeOverrides.getValidated() != null) {
            for (OverrideValue override : changeOverrides.getValidated().getOverrideValue()) {
                mEvaluatedOverrides.put(override.getPackageName(), override.getEnabled());
                mRawOverrides.put(override.getPackageName(),
                        new PackageOverride.Builder().setEnabled(
                                override.getEnabled()).build());
            }
        }

        // Load raw overrides
        if (changeOverrides.getRaw() != null) {
            for (RawOverrideValue override : changeOverrides.getRaw().getRawOverrideValue()) {
                PackageOverride packageOverride = new PackageOverride.Builder()
                        .setMinVersionCode(override.getMinVersionCode())
                        .setMaxVersionCode(override.getMaxVersionCode())
                        .setEnabled(override.getEnabled())
                        .build();
                mRawOverrides.put(override.getPackageName(), packageOverride);
            }
        }
    }

    synchronized ChangeOverrides saveOverrides() {
        if (mRawOverrides.isEmpty()) {
            return null;
        }
        ChangeOverrides changeOverrides = new ChangeOverrides();
        changeOverrides.setChangeId(getId());
        ChangeOverrides.Raw rawOverrides = new ChangeOverrides.Raw();
        List<RawOverrideValue> rawList = rawOverrides.getRawOverrideValue();
        for (Map.Entry<String, PackageOverride> entry : mRawOverrides.entrySet()) {
            RawOverrideValue override = new RawOverrideValue();
            override.setPackageName(entry.getKey());
            override.setMinVersionCode(entry.getValue().getMinVersionCode());
            override.setMaxVersionCode(entry.getValue().getMaxVersionCode());
            override.setEnabled(entry.getValue().isEnabled());
            rawList.add(override);
        }
        changeOverrides.setRaw(rawOverrides);

        ChangeOverrides.Validated validatedOverrides = new ChangeOverrides.Validated();
        List<OverrideValue> validatedList = validatedOverrides.getOverrideValue();
        for (Map.Entry<String, Boolean> entry : mEvaluatedOverrides.entrySet()) {
            OverrideValue override = new OverrideValue();
            override.setPackageName(entry.getKey());
            override.setEnabled(entry.getValue());
            validatedList.add(override);
        }
        changeOverrides.setValidated(validatedOverrides);
        return changeOverrides;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ChangeId(")
                .append(getId());
        if (getName() != null) {
            sb.append("; name=").append(getName());
        }
        if (getEnableSinceTargetSdk() != -1) {
            sb.append("; enableSinceTargetSdk=").append(getEnableSinceTargetSdk());
        }
        if (getDisabled()) {
            sb.append("; disabled");
        }
        if (getLoggingOnly()) {
            sb.append("; loggingOnly");
        }
        if (!mEvaluatedOverrides.isEmpty()) {
            sb.append("; packageOverrides=").append(mEvaluatedOverrides);
        }
        if (!mRawOverrides.isEmpty()) {
            sb.append("; rawOverrides=").append(mRawOverrides);
        }
        if (getOverridable()) {
            sb.append("; overridable");
        }
        return sb.append(")").toString();
    }

    private synchronized void notifyListener(String packageName) {
        if (mListener != null) {
            mListener.onCompatChange(packageName);
        }
    }
}
