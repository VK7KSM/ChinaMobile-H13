.class Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;
.super Ljava/lang/Object;
.source "AudioUtils.java"

# interfaces
.implements Ljava/lang/Runnable;


# annotations
.annotation system Ldalvik/annotation/EnclosingClass;
    value = Lcom/bozhou/interphone/util/AudioUtils;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x2
    name = "AudioWrite"
.end annotation


# instance fields
.field private audioTrack:Landroid/media/AudioTrack;

.field private handler:Landroid/os/Handler;

.field private running:Z

.field final synthetic this$0:Lcom/bozhou/interphone/util/AudioUtils;


# direct methods
.method public constructor <init>(Lcom/bozhou/interphone/util/AudioUtils;Landroid/media/AudioTrack;)V
    .locals 0

    .line 57
    iput-object p1, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->this$0:Lcom/bozhou/interphone/util/AudioUtils;

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 58
    iput-object p2, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->audioTrack:Landroid/media/AudioTrack;

    .line 59
    new-instance p1, Landroid/os/HandlerThread;

    const-string p2, "audio_write"

    invoke-direct {p1, p2}, Landroid/os/HandlerThread;-><init>(Ljava/lang/String;)V

    .line 60
    invoke-virtual {p1}, Landroid/os/HandlerThread;->start()V

    .line 61
    new-instance p2, Landroid/os/Handler;

    invoke-virtual {p1}, Landroid/os/HandlerThread;->getLooper()Landroid/os/Looper;

    move-result-object p1

    invoke-direct {p2, p1}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V

    iput-object p2, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->handler:Landroid/os/Handler;

    return-void
.end method


# virtual methods
.method public isWorking()Z
    .locals 1

    .line 77
    iget-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->running:Z

    return v0
.end method

.method public run()V
    .locals 4

    const/16 v0, 0x400

    new-array v1, v0, [B

    .line 83
    :goto_0
    iget-boolean v2, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->running:Z

    if-eqz v2, :cond_0

    .line 84
    iget-object v2, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->audioTrack:Landroid/media/AudioTrack;

    const/4 v3, 0x0

    invoke-virtual {v2, v1, v3, v0}, Landroid/media/AudioTrack;->write([BII)I

    goto :goto_0

    :cond_0
    return-void
.end method

.method public start()V
    .locals 1

    const/4 v0, 0x1

    .line 65
    iput-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->running:Z

    .line 66
    iget-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->audioTrack:Landroid/media/AudioTrack;

    invoke-virtual {v0}, Landroid/media/AudioTrack;->play()V

    .line 67
    iget-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->handler:Landroid/os/Handler;

    invoke-virtual {v0, p0}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z

    return-void
.end method

.method public stop()V
    .locals 1

    .line 71
    iget-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->audioTrack:Landroid/media/AudioTrack;

    invoke-virtual {v0}, Landroid/media/AudioTrack;->pause()V

    .line 72
    iget-object v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->audioTrack:Landroid/media/AudioTrack;

    invoke-virtual {v0}, Landroid/media/AudioTrack;->flush()V

    const/4 v0, 0x0

    .line 73
    iput-boolean v0, p0, Lcom/bozhou/interphone/util/AudioUtils$AudioWrite;->running:Z

    return-void
.end method
