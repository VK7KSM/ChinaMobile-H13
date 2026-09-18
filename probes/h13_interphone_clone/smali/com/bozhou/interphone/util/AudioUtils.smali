.class public Lcom/bozhou/interphone/util/AudioUtils;
.super Ljava/lang/Object;
.source "AudioUtils.java"


# annotations
.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;
    }
.end annotation


# static fields
.field private static INSTANCE:Lcom/bozhou/interphone/util/AudioUtils; = null

.field private static final TAG:Ljava/lang/String; = "AudioUtils"


# instance fields
.field private audioTrack:Landroid/media/AudioTrack;

.field private audioWrite:Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;

.field private playing:Z


# direct methods
.method static constructor <clinit>()V
    .locals 1

    .line 16
    new-instance v0, Lcom/bozhou/interphone/util/AudioUtils;

    invoke-direct {v0}, Lcom/bozhou/interphone/util/AudioUtils;-><init>()V

    sput-object v0, Lcom/bozhou/interphone/util/AudioUtils;->INSTANCE:Lcom/bozhou/interphone/util/AudioUtils;

    return-void
.end method

.method private constructor <init>()V
    .locals 8

    .line 23
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 24
    new-instance v7, Landroid/media/AudioTrack;

    const/4 v1, 0x3

    const/16 v2, 0x1f40

    const/4 v3, 0x4

    const/4 v4, 0x2

    const/16 v5, 0x400

    const/4 v6, 0x1

    move-object v0, v7

    invoke-direct/range {v0 .. v6}, Landroid/media/AudioTrack;-><init>(IIIIII)V

    iput-object v7, p0, Lcom/bozhou/interphone/util/AudioUtils;->audioTrack:Landroid/media/AudioTrack;

    .line 27
    new-instance v0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;

    invoke-direct {v0, p0, v7}, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;-><init>(Lcom/bozhou/interphone/util/AudioUtils;Landroid/media/AudioTrack;)V

    iput-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->audioWrite:Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;

    return-void
.end method

.method public static getInstance()Lcom/bozhou/interphone/util/AudioUtils;
    .locals 1

    .line 31
    sget-object v0, Lcom/bozhou/interphone/util/AudioUtils;->INSTANCE:Lcom/bozhou/interphone/util/AudioUtils;

    return-object v0
.end method


# virtual methods
.method public playSilentByte()V
    .locals 2

    .line 35
    iget-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->playing:Z

    if-eqz v0, :cond_0

    return-void

    :cond_0
    const-string v0, "AudioUtils"

    const-string v1, "\u64ad\u653e\u7a7a\u767d\u97f3\u9891"

    .line 38
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    const/4 v0, 0x1

    .line 39
    iput-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->playing:Z

    .line 40
    iget-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->audioWrite:Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;

    invoke-virtual {v0}, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->start()V

    return-void
.end method

.method public stopSilentByte()V
    .locals 2

    .line 44
    iget-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->playing:Z

    if-nez v0, :cond_0

    return-void

    :cond_0
    const-string v0, "AudioUtils"

    const-string v1, "\u505c\u6b62\u64ad\u653e\u7a7a\u767d\u97f3\u9891"

    .line 47
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 48
    iget-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->audioWrite:Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;

    invoke-virtual {v0}, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->stop()V

    const/4 v0, 0x0

    .line 49
    iput-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils;->playing:Z

    return-void
.end method
