.class public Lcom/bozhou/interphone/util/ToastUtils;
.super Ljava/lang/Object;
.source "ToastUtils.java"


# static fields
.field private static toast:Landroid/widget/Toast;


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 10
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static showToast(Landroid/content/Context;I)V
    .locals 0

    .line 14
    invoke-virtual {p0, p1}, Landroid/content/Context;->getString(I)Ljava/lang/String;

    move-result-object p1

    invoke-static {p0, p1}, Lcom/bozhou/interphone/util/ToastUtils;->showToast(Landroid/content/Context;Ljava/lang/String;)V

    return-void
.end method

.method public static showToast(Landroid/content/Context;Ljava/lang/String;)V
    .locals 1

    const/16 v0, 0x11

    .line 22
    invoke-static {p0, p1, v0}, Lcom/bozhou/interphone/util/ToastUtils;->showToast(Landroid/content/Context;Ljava/lang/String;I)V

    return-void
.end method

.method public static showToast(Landroid/content/Context;Ljava/lang/String;I)V
    .locals 6

    const/4 v3, 0x0

    const/4 v4, 0x0

    const/4 v5, 0x0

    move-object v0, p0

    move-object v1, p1

    move v2, p2

    .line 26
    invoke-static/range {v0 .. v5}, Lcom/bozhou/interphone/util/ToastUtils;->showToast(Landroid/content/Context;Ljava/lang/String;IIIZ)V

    return-void
.end method

.method public static showToast(Landroid/content/Context;Ljava/lang/String;IIIZ)V
    .locals 1

    .line 30
    sget-object v0, Lcom/bozhou/interphone/util/ToastUtils;->toast:Landroid/widget/Toast;

    if-eqz v0, :cond_0

    .line 31
    invoke-virtual {v0}, Landroid/widget/Toast;->cancel()V

    :cond_0
    const/4 v0, 0x0

    .line 33
    invoke-static {p0, p1, v0}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;

    move-result-object p0

    sput-object p0, Lcom/bozhou/interphone/util/ToastUtils;->toast:Landroid/widget/Toast;

    if-nez p5, :cond_1

    .line 35
    invoke-virtual {p0, p2, p3, p4}, Landroid/widget/Toast;->setGravity(III)V

    .line 37
    :cond_1
    sget-object p0, Lcom/bozhou/interphone/util/ToastUtils;->toast:Landroid/widget/Toast;

    invoke-virtual {p0}, Landroid/widget/Toast;->show()V

    return-void
.end method

.method public static showToastDefault(Landroid/content/Context;Ljava/lang/String;)V
    .locals 6

    const/16 v2, 0x11

    const/4 v3, 0x0

    const/4 v4, 0x0

    const/4 v5, 0x1

    move-object v0, p0

    move-object v1, p1

    .line 18
    invoke-static/range {v0 .. v5}, Lcom/bozhou/interphone/util/ToastUtils;->showToast(Landroid/content/Context;Ljava/lang/String;IIIZ)V

    return-void
.end method
