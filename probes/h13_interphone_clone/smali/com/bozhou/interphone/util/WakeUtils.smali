.class public Lcom/bozhou/interphone/util/WakeUtils;
.super Ljava/lang/Object;
.source "WakeUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 9
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static wakeScreen(Landroid/content/Context;)V
    .locals 1

    const-string v0, "power"

    .line 11
    invoke-virtual {p0, v0}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;

    move-result-object p0

    check-cast p0, Landroid/os/PowerManager;

    .line 12
    invoke-static {p0}, Lcom/bozhou/interphone/util/WakeUtils;->wakeScreen(Landroid/os/PowerManager;)V

    return-void
.end method

.method public static wakeScreen(Landroid/os/PowerManager;)V
    .locals 2

    const v0, 0x3000001a

    const-string v1, "dmr:tag"

    .line 16
    invoke-virtual {p0, v0, v1}, Landroid/os/PowerManager;->newWakeLock(ILjava/lang/String;)Landroid/os/PowerManager$WakeLock;

    move-result-object p0

    const-wide/16 v0, 0x3e8

    .line 18
    invoke-virtual {p0, v0, v1}, Landroid/os/PowerManager$WakeLock;->acquire(J)V

    return-void
.end method
