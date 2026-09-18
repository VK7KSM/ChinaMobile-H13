.class public Lcom/bozhou/interphone/util/VolumeUtils;
.super Ljava/lang/Object;
.source "VolumeUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 10
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static adjustModuleVolume(I)I
    .locals 1

    const/4 v0, 0x1

    if-ge p0, v0, :cond_0

    const/4 p0, 0x1

    :cond_0
    const/16 v0, 0x8

    if-le p0, v0, :cond_1

    const/16 p0, 0x8

    :cond_1
    return p0
.end method

.method private static getCurrentMusicVolume(Landroid/media/AudioManager;)I
    .locals 1

    const/4 v0, 0x3

    .line 13
    invoke-virtual {p0, v0}, Landroid/media/AudioManager;->getStreamVolume(I)I

    move-result p0

    return p0
.end method

.method private static getMaxMusicVolume(Landroid/media/AudioManager;)I
    .locals 1

    const/4 v0, 0x3

    .line 17
    invoke-virtual {p0, v0}, Landroid/media/AudioManager;->getStreamMaxVolume(I)I

    move-result p0

    return p0
.end method

.method public static resolveRealVolume(Landroid/media/AudioManager;)I
    .locals 1

    const/16 v0, 0x8

    .line 31
    invoke-static {p0, v0}, Lcom/bozhou/interphone/util/VolumeUtils;->resolveVolumeFromMusic(Landroid/media/AudioManager;I)I

    move-result p0

    return p0
.end method

.method public static resolveVolume(III)I
    .locals 0

    int-to-float p0, p0

    add-int/lit8 p1, p1, -0x1

    int-to-float p1, p1

    div-float/2addr p0, p1

    add-int/lit8 p2, p2, -0x1

    int-to-float p1, p2

    mul-float p0, p0, p1

    float-to-double p0, p0

    .line 42
    invoke-static {p0, p1}, Ljava/lang/Math;->ceil(D)D

    move-result-wide p0

    double-to-int p0, p0

    return p0
.end method

.method public static resolveVolumeFromMusic(Landroid/media/AudioManager;I)I
    .locals 1

    .line 35
    invoke-static {p0}, Lcom/bozhou/interphone/util/VolumeUtils;->getCurrentMusicVolume(Landroid/media/AudioManager;)I

    move-result v0

    .line 36
    invoke-static {p0}, Lcom/bozhou/interphone/util/VolumeUtils;->getMaxMusicVolume(Landroid/media/AudioManager;)I

    move-result p0

    .line 37
    invoke-static {v0, p0, p1}, Lcom/bozhou/interphone/util/VolumeUtils;->resolveVolume(III)I

    move-result p0

    return p0
.end method
