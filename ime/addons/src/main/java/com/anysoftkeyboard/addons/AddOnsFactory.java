/*
 * Copyright (c) 2013 Menny Even-Danan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.anysoftkeyboard.addons;

import static java.util.Collections.unmodifiableList;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Xml;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.XmlRes;
import com.anysoftkeyboard.base.utils.Logger;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public abstract class AddOnsFactory<E extends AddOn> {
  private static final String XML_PREF_ID_ATTRIBUTE = "id";
  private static final String XML_NAME_RES_ID_ATTRIBUTE = "nameResId";
  private static final String XML_DESCRIPTION_ATTRIBUTE = "description";
  private static final String XML_SORT_INDEX_ATTRIBUTE = "index";
  private static final String XML_DEV_ADD_ON_ATTRIBUTE = "devOnly";
  private static final String XML_HIDDEN_ADD_ON_ATTRIBUTE = "hidden";
  @NonNull protected final Context mContext;
  protected final String mTag;
  protected final SharedPreferences mSharedPreferences;
  final ArrayList<E> mAddOns = new ArrayList<>();
  final HashMap<String, E> mAddOnsById = new HashMap<>();
  final String mDefaultAddOnId;

  /**
   * This is the interface name that a broadcast receiver implementing an external addon should say
   * that it supports -- that is, this is the action it uses for its intent filter.
   */
  private final String mReceiverInterface;

  /**
   * Name under which an external addon broadcast receiver component publishes information about
   * itself.
   */
  private final String mReceiverMetaData;

  private final boolean mReadExternalPacksToo;
  private final String mRootNodeTag;
  private final String mAddonNodeTag;
  @XmlRes private final int mBuildInAddOnsResId;
  private final boolean mDevAddOnsIncluded;

  // NOTE: this should only be used when interacting with shared-prefs!
  private final String mPrefIdPrefix;

  protected AddOnsFactory(
      @NonNull Context context,
      @NonNull SharedPreferences sharedPreferences,
      String tag,
      String receiverInterface,
      String receiverMetaData,
      String rootNodeTag,
      String addonNodeTag,
      @NonNull String prefIdPrefix,
      @XmlRes int buildInAddonResId,
      @StringRes int defaultAddOnStringId,
      boolean readExternalPacksToo,
      boolean isDebugBuild) {
    mContext = context;
    mTag = tag;
    mReceiverInterface = receiverInterface;
    mReceiverMetaData = receiverMetaData;
    mRootNodeTag = rootNodeTag;
    mAddonNodeTag = addonNodeTag;
    if (TextUtils.isEmpty(prefIdPrefix)) {
      throw new IllegalArgumentException("prefIdPrefix can not be empty!");
    }
    mPrefIdPrefix = prefIdPrefix;
    mBuildInAddOnsResId = buildInAddonResId;
    if (buildInAddonResId == AddOn.INVALID_RES_ID) {
      throw new IllegalArgumentException("A built-in addon list MUST be provided!");
    }
    mReadExternalPacksToo = readExternalPacksToo;
    mDevAddOnsIncluded = isDebugBuild;
    mDefaultAddOnId = defaultAddOnStringId == 0 ? null : context.getString(defaultAddOnStringId);
    mSharedPreferences = sharedPreferences;

    if (isDebugBuild && readExternalPacksToo) {
      Logger.d(
          mTag,
          "Will read external addons with ACTION '%s' and meta-data '%s'",
          mReceiverInterface,
          mReceiverMetaData);
    }
  }

  @Nullable
  protected static CharSequence getTextFromResourceOrText(
      Context context, AttributeSet attrs, String attributeName) {
    final int stringResId =
        attrs.getAttributeResourceValue(null, attributeName, AddOn.INVALID_RES_ID);
    if (stringResId != AddOn.INVALID_RES_ID) {
      return context.getResources().getString(stringResId);
    } else {
      return attrs.getAttributeValue(null, attributeName);
    }
  }

  public static void onExternalPackChanged(
      Intent eventIntent, OnCriticalAddOnChangeListener ime, AddOnsFactory<?>... factories) {
    boolean cleared = false;
    for (AddOnsFactory<?> factory : factories) {
      try {
        if (factory.isEventRequiresCacheRefresh(eventIntent)) {
          cleared = true;
          Logger.d(
              "AddOnsFactory",
              factory.getClass().getName() + " will handle this package-changed event.");
          factory.clearAddOnList();
        }
      } catch (PackageManager.NameNotFoundException e) {
        Logger.w("AddOnsFactory", e, "Failed to notify onExternalPackChanged on %s", factory);
      }
    }
    if (cleared) ime.onAddOnsCriticalChange();
  }

  public static void onConfigurationChanged(
      @NonNull Configuration newConfig, AddOnsFactory<?>... factories) {
    for (AddOnsFactory<?> factory : factories) {
      for (AddOn addOn : factory.mAddOns) {
        if (addOn instanceof AddOnImpl) {
          ((AddOnImpl) addOn).setNewConfiguration(newConfig);
        }
      }
    }
  }

  public final List<E> getEnabledAddOns() {
    List<String> enabledIds = getEnabledIds();
    List<E> addOns = new ArrayList<>(enabledIds.size());
    for (String enabledId : enabledIds) {
      E addOn = getAddOnById(enabledId);
      if (addOn != null) addOns.add(addOn);
    }

    return Collections.unmodifiableList(addOns);
  }

  public boolean isAddOnEnabled(String addOnId) {
    return mSharedPreferences.getBoolean(mPrefIdPrefix + addOnId, isAddOnEnabledByDefault(addOnId));
  }

  final void setAddOnEnableValueInPrefs(
      SharedPreferences.Editor editor, String addOnId, boolean enabled) {
    editor.putBoolean(mPrefIdPrefix + addOnId, enabled);
  }

  public abstract void setAddOnEnabled(String addOnId, boolean enabled);

  protected boolean isAddOnEnabledByDefault(@NonNull String addOnId) {
    return false;
  }

  public final E getEnabledAddOn() {
    return getEnabledAddOns().get(0);
  }

  public final synchronized List<String> getEnabledIds() {
    ArrayList<String> enabledIds = new ArrayList<>();
    for (E addOn : getAllAddOns()) {
      final String addOnId = addOn.getId();
      if (isAddOnEnabled(addOnId)) enabledIds.add(addOnId);
    }

    // ensuring at least one add-on is there
    if (enabledIds.size() == 0 && !TextUtils.isEmpty(mDefaultAddOnId)) {
      enabledIds.add(mDefaultAddOnId);
    }

    return Collections.unmodifiableList(enabledIds);
  }

  private boolean isEventRequiresCacheRefresh(Intent eventIntent) throws NameNotFoundException {
    String action = eventIntent.getAction();
    String packageNameSchemePart = eventIntent.getData().getSchemeSpecificPart();
    if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
      // will reset only if the new package has my addons
      boolean hasAddon = isPackageContainAnAddon(packageNameSchemePart);
      if (hasAddon) {
        Logger.d(
            mTag,
            "It seems that an addon exists in a newly installed package "
                + packageNameSchemePart
                + ". I need to reload stuff.");
        return true;
      }
    } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)
        || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
      // If I'm managing OR it contains an addon (could be new feature in the package), I want
      // to reset.
      boolean isPackagedManaged = isPackageManaged(packageNameSchemePart);
      if (isPackagedManaged) {
        Logger.d(
            mTag,
            "It seems that an addon I use (in package "
                + packageNameSchemePart
                + ") has been changed. I need to reload stuff.");
        return true;
      } else {
        boolean hasAddon = isPackageContainAnAddon(packageNameSchemePart);
        if (hasAddon) {
          Logger.d(
              mTag,
              "It seems that an addon exists in an updated package "
                  + packageNameSchemePart
                  + ". I need to reload stuff.");
          return true;
        }
      }
    } else // removed
    {
      // so only if I manage this package, I want to reset
      boolean isPackagedManaged = isPackageManaged(packageNameSchemePart);
      if (isPackagedManaged) {
        Logger.d(
            mTag,
            "It seems that an addon I use (in package "
                + packageNameSchemePart
                + ") has been removed. I need to reload stuff.");
        return true;
      }
    }
    return false;
  }

  private boolean isPackageManaged(String packageNameSchemePart) {
    for (AddOn addOn : mAddOnsById.values()) {
      if (addOn.getPackageName().equals(packageNameSchemePart)) {
        return true;
      }
    }

    return false;
  }

  private boolean isPackageContainAnAddon(String packageNameSchemePart)
      throws NameNotFoundException {
    PackageInfo newPackage =
        mContext
            .getPackageManager()
            .getPackageInfo(
                packageNameSchemePart, PackageManager.GET_RECEIVERS + PackageManager.GET_META_DATA);
    if (newPackage.receivers != null) {
      ActivityInfo[] receivers = newPackage.receivers;
      for (ActivityInfo aReceiver : receivers) {
        // issue 904
        if (aReceiver == null
            || aReceiver.applicationInfo == null
            || !aReceiver.enabled
            || !aReceiver.applicationInfo.enabled) {
          continue;
        }
        try (final XmlResourceParser xml =
            aReceiver.loadXmlMetaData(mContext.getPackageManager(), mReceiverMetaData)) {
          if (xml != null) {
            return true;
          }
        }
      }
    }

    return false;
  }

  @CallSuper
  protected synchronized void clearAddOnList() {
    mAddOns.clear();
    mAddOnsById.clear();
  }

  public synchronized E getAddOnById(String id) {
    if (mAddOnsById.size() == 0) {
      loadAddOns();
    }
    return mAddOnsById.get(id);
  }

  public final synchronized List<E> getAllAddOns() {
    Logger.d(mTag, "getAllAddOns has %d add on for %s", mAddOns.size(), getClass().getName());
    if (mAddOns.size() == 0) {
      loadAddOns();
    }
    Logger.d(
        mTag, "getAllAddOns will return %d add on for %s", mAddOns.size(), getClass().getName());
    return unmodifiableList(mAddOns);
  }

  @CallSuper
  protected void loadAddOns() {
    clearAddOnList();

    List<E> local = getAddOnsFromLocalResId(mBuildInAddOnsResId);
    for (E addon : local) {
      Logger.d(mTag, "Local add-on %s loaded", addon.getId());
    }
    if (local.isEmpty()) {
      throw new IllegalStateException("No built-in addons were found for " + getClass().getName());
    }
    mAddOns.addAll(local);

    List<E> external = getExternalAddOns();
    for (E addon : external) {
      Logger.d(mTag, "External add-on %s loaded", addon.getId());
    }
    // ensures there are no duplicates
    // also, allow overriding internal packs with externals with the same ID
    mAddOns.removeAll(external);
    mAddOns.addAll(external);
    Logger.d(mTag, "Have %d add on for %s", mAddOns.size(), getClass().getName());

    for (E addOn : mAddOns) {
      mAddOnsById.put(addOn.getId(), addOn);
    }
    // removing hidden addons from global list, so hidden addons exist only in the mapping
    for (E addOn : mAddOnsById.values()) {
      if (addOn instanceof AddOnImpl && ((AddOnImpl) addOn).isHiddenAddon()) {
        mAddOns.remove(addOn);
      }
    }

    // sorting the keyboards according to the requested
    // sort order (from minimum to maximum)
    Collections.sort(mAddOns, new AddOnsComparator(mContext.getPackageName()));
    Logger.d(mTag, "Have %d add on for %s (after sort)", mAddOns.size(), getClass().getName());
  }

  private List<E> getExternalAddOns() {
    final PackageManager packageManager = mContext.getPackageManager();
    final List<ResolveInfo> broadcastReceivers =
        packageManager.queryBroadcastReceivers(
            new Intent(mReceiverInterface), PackageManager.GET_META_DATA);

    final List<E> externalAddOns = new ArrayList<>();

    for (final ResolveInfo receiver : broadcastReceivers) {
      if (receiver.activityInfo == null) {
        Logger.e(
            mTag,
            "BroadcastReceiver has null ActivityInfo. Receiver's label is "
                + receiver.loadLabel(packageManager));
        Logger.e(mTag, "Is the external keyboard a service instead of BroadcastReceiver?");
        // Skip to next receiver
        continue;
      }

      if (!receiver.activityInfo.enabled || !receiver.activityInfo.applicationInfo.enabled) {
        continue;
      }

      if (!mReadExternalPacksToo
          && !mContext.getPackageName().equalsIgnoreCase(receiver.activityInfo.packageName)) {
        // Skipping external packages
        continue;
      }
      try {
        final Context externalPackageContext =
            mContext.createPackageContext(
                receiver.activityInfo.packageName, Context.CONTEXT_IGNORE_SECURITY);
        final List<E> packageAddOns =
            getAddOnsFromActivityInfo(externalPackageContext, receiver.activityInfo);

        externalAddOns.addAll(packageAddOns);
      } catch (final NameNotFoundException e) {
        Logger.e(mTag, "Did not find package: " + receiver.activityInfo.packageName);
      }
    }

    return externalAddOns;
  }

  private List<E> getAddOnsFromLocalResId(int addOnsResId) {
    try (final XmlResourceParser xml = mContext.getResources().getXml(addOnsResId)) {
      return parseAddOnsFromXml(mContext, xml, true);
    }
  }

  private List<E> getAddOnsFromActivityInfo(Context packContext, ActivityInfo ai) {
    try (final XmlResourceParser xml =
        ai.loadXmlMetaData(mContext.getPackageManager(), mReceiverMetaData)) {
      if (xml == null) {
        // issue 718: maybe a bad package?
        return Collections.emptyList();
      }
      return parseAddOnsFromXml(packContext, xml, false);
    }
  }

  private ArrayList<E> parseAddOnsFromXml(Context packContext, XmlPullParser xml, boolean isLocal) {
    final ArrayList<E> addOns = new ArrayList<>();
    try {
      int event;
      boolean inRoot = false;
      while ((event = xml.next()) != XmlPullParser.END_DOCUMENT) {
        final String tag = xml.getName();
        if (event == XmlPullParser.START_TAG) {
          if (mRootNodeTag.equals(tag)) {
            inRoot = true;
          } else if (inRoot && mAddonNodeTag.equals(tag)) {
            final AttributeSet attrs = Xml.asAttributeSet(xml);
            E addOn = createAddOnFromXmlAttributes(attrs, packContext);
            if (addOn != null) {
              addOns.add(addOn);
            }
          }
        } else if (event == XmlPullParser.END_TAG && mRootNodeTag.equals(tag)) {
          inRoot = false;
          break;
        }
      }
    } catch (final IOException e) {
      Logger.e(mTag, "IO error:" + e);
      if (isLocal) throw new RuntimeException(e);
      e.printStackTrace();
    } catch (final XmlPullParserException e) {
      Logger.e(mTag, "Parse error:" + e);
      if (isLocal) throw new RuntimeException(e);
      e.printStackTrace();
    }

    return addOns;
  }

  @Nullable
  private E createAddOnFromXmlAttributes(AttributeSet attrs, Context packContext) {
    final CharSequence prefId =
        getTextFromResourceOrText(packContext, attrs, XML_PREF_ID_ATTRIBUTE);
    final CharSequence name =
        getTextFromResourceOrText(packContext, attrs, XML_NAME_RES_ID_ATTRIBUTE);

    if (!mDevAddOnsIncluded
        && attrs.getAttributeBooleanValue(null, XML_DEV_ADD_ON_ATTRIBUTE, false)) {
      Logger.w(
          mTag,
          "Discarding add-on %s (name %s) since it is marked as DEV addon, and we're not"
              + " a TESTING_BUILD build.",
          prefId,
          name);
      return null;
    }

    final int apiVersion = getApiVersion(packContext);
    final boolean isHidden =
        attrs.getAttributeBooleanValue(null, XML_HIDDEN_ADD_ON_ATTRIBUTE, false);
    final CharSequence description =
        getTextFromResourceOrText(packContext, attrs, XML_DESCRIPTION_ATTRIBUTE);

    final int sortIndex = attrs.getAttributeUnsignedIntValue(null, XML_SORT_INDEX_ATTRIBUTE, 1);

    // asserting
    if (TextUtils.isEmpty(prefId) || TextUtils.isEmpty(name)) {
      Logger.e(
          mTag,
          "External add-on does not include all mandatory details! Will not create" + " add-on.");
      return null;
    } else {
      Logger.d(mTag, "External addon details: prefId:" + prefId + " name:" + name);
      return createConcreteAddOn(
          mContext, packContext, apiVersion, prefId, name, description, isHidden, sortIndex, attrs);
    }
  }

  private int getApiVersion(Context packContext) {
    try {
      final Resources resources = packContext.getResources();
      final int identifier =
          resources.getIdentifier(
              "anysoftkeyboard_api_version_code", "integer", packContext.getPackageName());
      if (identifier == 0) return 0;

      return resources.getInteger(identifier);
    } catch (Exception e) {
      Logger.w(mTag, "Failed to load api-version for package %s", packContext.getPackageName());
      return 0;
    }
  }

  protected abstract E createConcreteAddOn(
      Context askContext,
      Context context,
      int apiVersion,
      CharSequence prefId,
      CharSequence name,
      CharSequence description,
      boolean isHidden,
      int sortIndex,
      AttributeSet attrs);

  public interface OnCriticalAddOnChangeListener {
    void onAddOnsCriticalChange();
  }

  private static final class AddOnsComparator implements Comparator<AddOn>, Serializable {
    static final long serialVersionUID = 1276823L;

    private final String mAskPackageName;

    private AddOnsComparator(String askPackageName) {
      mAskPackageName = askPackageName;
    }

    @Override
    public int compare(AddOn k1, AddOn k2) {
      String c1 = k1.getPackageName();
      String c2 = k2.getPackageName();

      if (c1.equals(c2)) {
        return k1.getSortIndex() - k2.getSortIndex();
      } else if (c1.equals(mAskPackageName)) // I want to make sure ASK packages are first
      {
        return -1;
      } else if (c2.equals(mAskPackageName)) {
        return 1;
      } else {
        return c1.compareToIgnoreCase(c2);
      }
    }
  }

  public abstract static class SingleAddOnsFactory<E extends AddOn> extends AddOnsFactory<E> {

    protected SingleAddOnsFactory(
        @NonNull Context context,
        @NonNull SharedPreferences sharedPreferences,
        String tag,
        String receiverInterface,
        String receiverMetaData,
        String rootNodeTag,
        String addonNodeTag,
        String prefIdPrefix,
        @XmlRes int buildInAddonResId,
        @StringRes int defaultAddOnStringId,
        boolean readExternalPacksToo,
        boolean isTestingBuild) {
      super(
          context,
          sharedPreferences,
          tag,
          receiverInterface,
          receiverMetaData,
          rootNodeTag,
          addonNodeTag,
          prefIdPrefix,
          buildInAddonResId,
          defaultAddOnStringId,
          readExternalPacksToo,
          isTestingBuild);
    }

    @Override
    public void setAddOnEnabled(String addOnId, boolean enabled) {
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      if (enabled) {
        // ensuring addons are loaded.
        getAllAddOns();
        // disable any other addon
        for (String otherAddOnId : mAddOnsById.keySet()) {
          setAddOnEnableValueInPrefs(editor, otherAddOnId, TextUtils.equals(otherAddOnId, addOnId));
        }
      } else {
        // enabled the default, disable the requested
        // NOTE: can not directly disable a default addon!
        // you should enable something else, which will cause the current (default?)
        // add-on to be automatically disabled.
        setAddOnEnableValueInPrefs(editor, addOnId, false);
        setAddOnEnableValueInPrefs(editor, mDefaultAddOnId, true);
      }
      editor.apply();
    }
  }

  public abstract static class MultipleAddOnsFactory<E extends AddOn> extends AddOnsFactory<E> {
    private final String mSortedIdsPrefId;

    protected MultipleAddOnsFactory(
        @NonNull Context context,
        @NonNull SharedPreferences sharedPreferences,
        String tag,
        String receiverInterface,
        String receiverMetaData,
        String rootNodeTag,
        String addonNodeTag,
        String prefIdPrefix,
        @XmlRes int buildInAddonResId,
        @StringRes int defaultAddOnStringId,
        boolean readExternalPacksToo,
        boolean isTestingBuild) {
      super(
          context,
          sharedPreferences,
          tag,
          receiverInterface,
          receiverMetaData,
          rootNodeTag,
          addonNodeTag,
          prefIdPrefix,
          buildInAddonResId,
          defaultAddOnStringId,
          readExternalPacksToo,
          isTestingBuild);

      mSortedIdsPrefId = prefIdPrefix + "AddOnsFactory_order_key";
    }

    public final void setAddOnsOrder(Collection<E> addOnsOr) {
      List<String> ids = new ArrayList<>(addOnsOr.size());
      for (E addOn : addOnsOr) {
        ids.add(addOn.getId());
      }

      setAddOnIdsOrder(ids);
    }

    public final void setAddOnIdsOrder(Collection<String> enabledAddOnIds) {
      Set<String> storedKeys = new HashSet<>();
      StringBuilder orderValue = new StringBuilder();
      int currentOrderIndex = 0;
      for (String id : enabledAddOnIds) {
        // adding each once.
        if (!storedKeys.contains(id)) {
          storedKeys.add(id);
          if (mAddOnsById.containsKey(id)) {
            final E addOnToReorder = mAddOnsById.get(id);
            mAddOns.remove(addOnToReorder);
            mAddOns.add(currentOrderIndex, addOnToReorder);
            if (currentOrderIndex > 0) {
              orderValue.append(",");
            }
            orderValue.append(id);
            currentOrderIndex++;
          }
        }
      }

      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putString(mSortedIdsPrefId, orderValue.toString());
      editor.apply();
    }

    @Override
    protected void loadAddOns() {
      super.loadAddOns();

      // now forcing order
      String[] order = mSharedPreferences.getString(mSortedIdsPrefId, "").split(",", -1);
      int currentOrderIndex = 0;
      Set<String> seenIds = new HashSet<>();
      for (String id : order) {
        if (mAddOnsById.containsKey(id) && !seenIds.contains(id)) {
          seenIds.add(id);
          E addOnToReorder = mAddOnsById.get(id);
          mAddOns.remove(addOnToReorder);
          mAddOns.add(currentOrderIndex, addOnToReorder);
          currentOrderIndex++;
        }
      }
    }

    @Override
    public void setAddOnEnabled(String addOnId, boolean enabled) {
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      setAddOnEnableValueInPrefs(editor, addOnId, enabled);
      editor.apply();
    }

    @Override
    protected boolean isAddOnEnabledByDefault(@NonNull String addOnId) {
      return super.isAddOnEnabledByDefault(addOnId) || TextUtils.equals(mDefaultAddOnId, addOnId);
    }
  }
}
