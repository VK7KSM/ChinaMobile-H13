.class public Lcom/bozhou/interphone/util/SettingUtils;
.super Ljava/lang/Object;
.source "SettingUtils.java"


# static fields
.field private static final TAG:Ljava/lang/String; = "SettingUtils"

.field private static cacheEnabled:Ljava/lang/Boolean;


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 13
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static getMicHornUser(Landroid/content/Context;)I
    .locals 2

    .line 146
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    const/4 v1, 0x0

    if-nez v0, :cond_0

    return v1

    .line 149
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "mic_horn_user"

    invoke-static {p0, v0, v1}, Lcom/bozhou/settingslib/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result p0

    return p0
.end method

.method public static getNotificationPattern(Landroid/content/Context;)Ljava/lang/String;
    .locals 1

    .line 104
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const-string p0, "voice"

    return-object p0

    .line 107
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "notify_pattern"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static isHardwareIdle(Landroid/content/Context;)Z
    .locals 3

    .line 153
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    const/4 v1, 0x1

    if-nez v0, :cond_0

    return v1

    .line 156
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const/4 v0, 0x0

    const-string v2, "mic_horn_user"

    invoke-static {p0, v2, v0}, Lcom/bozhou/settingslib/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result p0

    if-nez p0, :cond_1

    goto :goto_0

    :cond_1
    const/4 v1, 0x0

    :goto_0
    return v1
.end method

.method public static isHardwareWeUsed(Landroid/content/Context;)Z
    .locals 2

    .line 161
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    const/4 v1, 0x0

    if-nez v0, :cond_0

    return v1

    .line 164
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "mic_horn_user"

    invoke-static {p0, v0, v1}, Lcom/bozhou/settingslib/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result p0

    const/4 v0, 0x2

    if-ne p0, v0, :cond_1

    const/4 v1, 0x1

    :cond_1
    return v1
.end method

.method public static isIncallWake(Landroid/content/Context;)Z
    .locals 2

    .line 139
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    const/4 v1, 0x0

    if-nez v0, :cond_0

    return v1

    .line 142
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "incall_wake"

    invoke-static {p0, v0, v1}, Lcom/bozhou/settingslib/Settings$Global;->getBoolean(Landroid/content/ContentResolver;Ljava/lang/String;Z)Z

    move-result p0

    return p0
.end method

.method public static isMajorStandby(Landroid/content/Context;)Z
    .locals 1

    .line 76
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSinglePrimary(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_1

    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isMultiPrimary(Landroid/content/Context;)Z

    move-result p0

    if-eqz p0, :cond_0

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    goto :goto_1

    :cond_1
    :goto_0
    const/4 p0, 0x1

    :goto_1
    return p0
.end method

.method public static isMessageNotify(Landroid/content/Context;)Z
    .locals 1

    .line 96
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const/4 p0, 0x0

    return p0

    .line 99
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "notify_pattern"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string v0, "message"

    .line 100
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    return p0
.end method

.method public static isMinorStandby(Landroid/content/Context;)Z
    .locals 1

    .line 80
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isMultiMode(Landroid/content/Context;)Z

    move-result v0

    if-eqz v0, :cond_0

    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isMultiPrimary(Landroid/content/Context;)Z

    move-result p0

    if-nez p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isMultiMode(Landroid/content/Context;)Z
    .locals 1

    .line 36
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const/4 p0, 0x0

    return p0

    .line 39
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "mode_strategy"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string v0, "multi"

    .line 40
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    return p0
.end method

.method public static isMultiPrimary(Landroid/content/Context;)Z
    .locals 2

    .line 65
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    const/4 v1, 0x0

    if-nez v0, :cond_0

    return v1

    .line 68
    :cond_0
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isMultiMode(Landroid/content/Context;)Z

    move-result v0

    if-eqz v0, :cond_1

    .line 69
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "multi_strategy"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string v0, "private"

    .line 70
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    return p0

    :cond_1
    return v1
.end method

.method public static isNopStandby(Landroid/content/Context;)Z
    .locals 1

    .line 84
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSingleMode(Landroid/content/Context;)Z

    move-result v0

    if-eqz v0, :cond_0

    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSinglePrimary(Landroid/content/Context;)Z

    move-result p0

    if-nez p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isSettingsEnabled(Landroid/content/Context;)Z
    .locals 3

    .line 183
    sget-object v0, Lcom/bozhou/interphone/util/SettingUtils;->cacheEnabled:Ljava/lang/Boolean;

    if-eqz v0, :cond_0

    .line 184
    invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z

    move-result p0

    return p0

    .line 186
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object p0

    .line 187
    sget-object v0, Lcom/bozhou/settingslib/Settings$Global;->CONTENT_URI:Landroid/net/Uri;

    invoke-virtual {v0}, Landroid/net/Uri;->getAuthority()Ljava/lang/String;

    move-result-object v0

    const/4 v1, 0x0

    invoke-virtual {p0, v0, v1}, Landroid/content/pm/PackageManager;->resolveContentProvider(Ljava/lang/String;I)Landroid/content/pm/ProviderInfo;

    move-result-object p0

    if-nez p0, :cond_1

    const-string v0, "SettingUtils"

    const-string v2, "\u72b6\u6001\u5f02\u5e38\uff0cSettings.Global\u4e0d\u53ef\u7528"

    .line 189
    invoke-static {v0, v2}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I

    :cond_1
    if-eqz p0, :cond_2

    const/4 v1, 0x1

    .line 191
    :cond_2
    invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    sput-object p0, Lcom/bozhou/interphone/util/SettingUtils;->cacheEnabled:Ljava/lang/Boolean;

    .line 192
    invoke-virtual {p0}, Ljava/lang/Boolean;->booleanValue()Z

    move-result p0

    return p0
.end method

.method public static isSingleMode(Landroid/content/Context;)Z
    .locals 1

    .line 23
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const/4 p0, 0x1

    return p0

    .line 26
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "mode_strategy"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string v0, "single"

    .line 27
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    return p0
.end method

.method public static isSinglePrimary(Landroid/content/Context;)Z
    .locals 1

    .line 49
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const/4 p0, 0x1

    return p0

    .line 52
    :cond_0
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSingleMode(Landroid/content/Context;)Z

    move-result v0

    if-eqz v0, :cond_1

    .line 53
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "single_strategy"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string v0, "private"

    .line 54
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    return p0

    :cond_1
    const/4 p0, 0x0

    return p0
.end method

.method public static isTTSOn(Landroid/content/Context;)Z
    .locals 2

    .line 132
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const/4 p0, 0x0

    return p0

    .line 135
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const/4 v0, 0x1

    const-string v1, "tts_on"

    invoke-static {p0, v1, v0}, Lcom/bozhou/settingslib/Settings$Global;->getBoolean(Landroid/content/ContentResolver;Ljava/lang/String;Z)Z

    move-result p0

    return p0
.end method

.method public static isVoiceNotify(Landroid/content/Context;)Z
    .locals 1

    .line 88
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    const/4 p0, 0x1

    return p0

    .line 91
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "notify_pattern"

    invoke-static {p0, v0}, Lcom/bozhou/settingslib/Settings$Global;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string v0, "voice"

    .line 92
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    return p0
.end method

.method public static setHardwareIdle(Landroid/content/Context;)V
    .locals 2

    .line 176
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    .line 179
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const/4 v0, 0x0

    const-string v1, "mic_horn_user"

    invoke-static {p0, v1, v0}, Lcom/bozhou/settingslib/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    return-void
.end method

.method public static setHardwareWeUsed(Landroid/content/Context;)V
    .locals 2

    .line 169
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    .line 172
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const/4 v0, 0x2

    const-string v1, "mic_horn_user"

    invoke-static {p0, v1, v0}, Lcom/bozhou/settingslib/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    return-void
.end method

.method public static setPrimary(Landroid/content/Context;)V
    .locals 5

    .line 115
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    .line 118
    :cond_0
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isMultiMode(Landroid/content/Context;)Z

    move-result v0

    const-string v1, "private"

    const-string v2, "multi_strategy"

    if-eqz v0, :cond_1

    .line 119
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    invoke-static {p0, v2, v1}, Lcom/bozhou/settingslib/Settings$Global;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

    goto :goto_0

    .line 121
    :cond_1
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v0

    const-string v3, "mode_strategy"

    const-string v4, "multi"

    invoke-static {v0, v3, v4}, Lcom/bozhou/settingslib/Settings$Global;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

    .line 122
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    invoke-static {p0, v2, v1}, Lcom/bozhou/settingslib/Settings$Global;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

    :goto_0
    return-void
.end method
