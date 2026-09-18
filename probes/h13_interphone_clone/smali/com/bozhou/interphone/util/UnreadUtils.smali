.class public Lcom/bozhou/interphone/util/UnreadUtils;
.super Ljava/lang/Object;
.source "UnreadUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 11
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static clearUnreadCount(Landroid/content/Context;)V
    .locals 1

    .line 44
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    :cond_0
    const/4 v0, 0x0

    .line 47
    invoke-static {p0, v0}, Lcom/bozhou/interphone/util/UnreadUtils;->setUnreadCount(Landroid/content/Context;I)V

    return-void
.end method

.method public static decrementUnreadCount(Landroid/content/Context;)V
    .locals 1

    .line 36
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    .line 39
    :cond_0
    invoke-static {p0}, Lcom/bozhou/interphone/util/UnreadUtils;->getUnreadCount(Landroid/content/Context;)I

    move-result v0

    add-int/lit8 v0, v0, -0x1

    .line 40
    invoke-static {p0, v0}, Lcom/bozhou/interphone/util/UnreadUtils;->setUnreadCount(Landroid/content/Context;I)V

    return-void
.end method

.method public static getUnreadCount(Landroid/content/Context;)I
    .locals 2

    .line 14
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    const/4 v1, 0x0

    if-nez v0, :cond_0

    return v1

    .line 17
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v0

    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object p0

    invoke-static {v0, p0, v1}, Lcom/bozhou/settingslib/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result p0

    return p0
.end method

.method public static incrementUnreadCount(Landroid/content/Context;)V
    .locals 1

    .line 28
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    .line 31
    :cond_0
    invoke-static {p0}, Lcom/bozhou/interphone/util/UnreadUtils;->getUnreadCount(Landroid/content/Context;)I

    move-result v0

    add-int/lit8 v0, v0, 0x1

    .line 32
    invoke-static {p0, v0}, Lcom/bozhou/interphone/util/UnreadUtils;->setUnreadCount(Landroid/content/Context;I)V

    return-void
.end method

.method public static setUnreadCount(Landroid/content/Context;I)V
    .locals 1

    .line 21
    invoke-static {p0}, Lcom/bozhou/interphone/util/SettingUtils;->isSettingsEnabled(Landroid/content/Context;)Z

    move-result v0

    if-nez v0, :cond_0

    return-void

    .line 24
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v0

    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object p0

    invoke-static {v0, p0, p1}, Lcom/bozhou/settingslib/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    return-void
.end method
