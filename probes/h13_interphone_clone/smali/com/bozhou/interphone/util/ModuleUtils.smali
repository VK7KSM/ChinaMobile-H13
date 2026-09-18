.class public abstract Lcom/bozhou/interphone/util/ModuleUtils;
.super Ljava/lang/Object;
.source "ModuleUtils.java"


# static fields
.field private static final FREQ_SECTION:Ljava/lang/String; = "sys/boptt/freq_section"

.field private static final SWITCH_MIC_PATH:Ljava/lang/String; = "sys/boptt/audio_switch"

.field private static final SWITCH_MODULE_PATH:Ljava/lang/String; = "sys/boptt/dmr_switch"

.field private static final SWITCH_PA_PATH:Ljava/lang/String; = "sys/boptt/pa_enable"

.field private static final TAG:Ljava/lang/String; = "ModuleUtils"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 15
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static getFrequencySectionCode()Ljava/lang/String;
    .locals 4

    const/4 v0, 0x0

    .line 222
    :try_start_0
    new-instance v1, Ljava/io/BufferedReader;

    new-instance v2, Ljava/io/FileReader;

    const-string v3, "sys/boptt/freq_section"

    invoke-direct {v2, v3}, Ljava/io/FileReader;-><init>(Ljava/lang/String;)V

    invoke-direct {v1, v2}, Ljava/io/BufferedReader;-><init>(Ljava/io/Reader;)V

    .line 223
    invoke-virtual {v1}, Ljava/io/BufferedReader;->readLine()Ljava/lang/String;

    move-result-object v0

    .line 224
    invoke-virtual {v1}, Ljava/io/BufferedReader;->close()V

    if-eqz v0, :cond_0

    .line 226
    invoke-virtual {v0}, Ljava/lang/String;->trim()Ljava/lang/String;

    move-result-object v0
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    :cond_0
    return-object v0
.end method

.method public static isHornOn()Z
    .locals 3

    const/4 v0, 0x0

    .line 202
    :try_start_0
    new-instance v1, Ljava/io/FileReader;

    const-string v2, "sys/boptt/pa_enable"

    invoke-direct {v1, v2}, Ljava/io/FileReader;-><init>(Ljava/lang/String;)V

    .line 203
    invoke-virtual {v1}, Ljava/io/FileReader;->read()I

    move-result v2

    int-to-char v2, v2

    .line 204
    invoke-virtual {v1}, Ljava/io/FileReader;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    const/16 v1, 0x31

    if-ne v2, v1, :cond_0

    const/4 v0, 0x1

    :catch_0
    :cond_0
    return v0
.end method

.method public static isMicOn()Z
    .locals 3

    const/4 v0, 0x0

    .line 143
    :try_start_0
    new-instance v1, Ljava/io/FileReader;

    const-string v2, "sys/boptt/audio_switch"

    invoke-direct {v1, v2}, Ljava/io/FileReader;-><init>(Ljava/lang/String;)V

    .line 144
    invoke-virtual {v1}, Ljava/io/FileReader;->read()I

    move-result v2

    int-to-char v2, v2

    .line 145
    invoke-virtual {v1}, Ljava/io/FileReader;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    const/16 v1, 0x31

    if-ne v2, v1, :cond_0

    const/4 v0, 0x1

    :catch_0
    :cond_0
    return v0
.end method

.method public static isModuleOn()Z
    .locals 3

    const/4 v0, 0x0

    .line 68
    :try_start_0
    new-instance v1, Ljava/io/FileReader;

    const-string v2, "sys/boptt/dmr_switch"

    invoke-direct {v1, v2}, Ljava/io/FileReader;-><init>(Ljava/lang/String;)V

    .line 69
    invoke-virtual {v1}, Ljava/io/FileReader;->read()I

    move-result v2

    int-to-char v2, v2

    .line 70
    invoke-virtual {v1}, Ljava/io/FileReader;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    const/16 v1, 0x31

    if-ne v2, v1, :cond_0

    const/4 v0, 0x1

    :catch_0
    :cond_0
    return v0
.end method

.method public static moduleUpgrade(Ljava/lang/String;)V
    .locals 3

    .line 213
    :try_start_0
    invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;

    move-result-object v0

    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v2, "/system/bin/dmr_upgrade -u "

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v0, p0}, Ljava/lang/Runtime;->exec(Ljava/lang/String;)Ljava/lang/Process;
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_0

    :catch_0
    move-exception p0

    .line 215
    invoke-virtual {p0}, Ljava/io/IOException;->printStackTrace()V

    :goto_0
    return-void
.end method

.method public static setHornOff()Z
    .locals 3

    .line 180
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->isHornOn()Z

    move-result v0

    const/4 v1, 0x1

    if-nez v0, :cond_0

    return v1

    :cond_0
    const-string v0, "ModuleUtils"

    const-string v2, "\u5173\u95edPA"

    .line 183
    invoke-static {v0, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 185
    :try_start_0
    new-instance v0, Ljava/io/FileWriter;

    const-string v2, "sys/boptt/pa_enable"

    invoke-direct {v0, v2}, Ljava/io/FileWriter;-><init>(Ljava/lang/String;)V

    const-string v2, "0"

    .line 186
    invoke-virtual {v0, v2}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V

    .line 187
    invoke-virtual {v0}, Ljava/io/FileWriter;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    .line 191
    invoke-static {}, Lcom/bozhou/interphone/util/AudioUtils;->getInstance()Lcom/bozhou/interphone/util/AudioUtils;

    move-result-object v0

    invoke-virtual {v0}, Lcom/bozhou/interphone/util/AudioUtils;->stopSilentByte()V

    return v1

    :catchall_0
    move-exception v0

    invoke-static {}, Lcom/bozhou/interphone/util/AudioUtils;->getInstance()Lcom/bozhou/interphone/util/AudioUtils;

    move-result-object v1

    invoke-virtual {v1}, Lcom/bozhou/interphone/util/AudioUtils;->stopSilentByte()V

    .line 192
    throw v0

    :catch_0
    const/4 v0, 0x0

    .line 191
    invoke-static {}, Lcom/bozhou/interphone/util/AudioUtils;->getInstance()Lcom/bozhou/interphone/util/AudioUtils;

    move-result-object v1

    invoke-virtual {v1}, Lcom/bozhou/interphone/util/AudioUtils;->stopSilentByte()V

    return v0
.end method

.method public static setHornOn()Z
    .locals 3

    .line 158
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->isHornOn()Z

    move-result v0

    const/4 v1, 0x1

    if-eqz v0, :cond_0

    return v1

    :cond_0
    const-string v0, "ModuleUtils"

    const-string v2, "\u6253\u5f00PA"

    .line 161
    invoke-static {v0, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 163
    :try_start_0
    new-instance v0, Ljava/io/FileWriter;

    const-string v2, "sys/boptt/pa_enable"

    invoke-direct {v0, v2}, Ljava/io/FileWriter;-><init>(Ljava/lang/String;)V

    const-string v2, "1"

    .line 164
    invoke-virtual {v0, v2}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V

    .line 165
    invoke-virtual {v0}, Ljava/io/FileWriter;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    .line 169
    invoke-static {}, Lcom/bozhou/interphone/util/AudioUtils;->getInstance()Lcom/bozhou/interphone/util/AudioUtils;

    move-result-object v0

    invoke-virtual {v0}, Lcom/bozhou/interphone/util/AudioUtils;->playSilentByte()V

    return v1

    :catchall_0
    move-exception v0

    invoke-static {}, Lcom/bozhou/interphone/util/AudioUtils;->getInstance()Lcom/bozhou/interphone/util/AudioUtils;

    move-result-object v1

    invoke-virtual {v1}, Lcom/bozhou/interphone/util/AudioUtils;->playSilentByte()V

    .line 170
    throw v0

    :catch_0
    const/4 v0, 0x0

    .line 169
    invoke-static {}, Lcom/bozhou/interphone/util/AudioUtils;->getInstance()Lcom/bozhou/interphone/util/AudioUtils;

    move-result-object v1

    invoke-virtual {v1}, Lcom/bozhou/interphone/util/AudioUtils;->playSilentByte()V

    return v0
.end method

.method public static setMicOff()Z
    .locals 3

    .line 123
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->isMicOn()Z

    move-result v0

    const/4 v1, 0x1

    if-nez v0, :cond_0

    return v1

    :cond_0
    const-string v0, "ModuleUtils"

    const-string v2, "\u5173\u95edMIC"

    .line 126
    invoke-static {v0, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 128
    :try_start_0
    new-instance v0, Ljava/io/FileWriter;

    const-string v2, "sys/boptt/audio_switch"

    invoke-direct {v0, v2}, Ljava/io/FileWriter;-><init>(Ljava/lang/String;)V

    const-string v2, "0"

    .line 129
    invoke-virtual {v0, v2}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V

    .line 130
    invoke-virtual {v0}, Ljava/io/FileWriter;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    return v1

    :catch_0
    const/4 v0, 0x0

    return v0
.end method

.method public static setMicOn()Z
    .locals 3

    .line 103
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->isMicOn()Z

    move-result v0

    const/4 v1, 0x1

    if-eqz v0, :cond_0

    return v1

    :cond_0
    const-string v0, "ModuleUtils"

    const-string v2, "\u6253\u5f00MIC"

    .line 106
    invoke-static {v0, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 108
    :try_start_0
    new-instance v0, Ljava/io/FileWriter;

    const-string v2, "sys/boptt/audio_switch"

    invoke-direct {v0, v2}, Ljava/io/FileWriter;-><init>(Ljava/lang/String;)V

    const-string v2, "1"

    .line 109
    invoke-virtual {v0, v2}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V

    .line 110
    invoke-virtual {v0}, Ljava/io/FileWriter;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    return v1

    :catch_0
    const/4 v0, 0x0

    return v0
.end method

.method public static setModuleOff()Z
    .locals 3

    .line 48
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->isModuleOn()Z

    move-result v0

    const/4 v1, 0x1

    if-nez v0, :cond_0

    return v1

    :cond_0
    const-string v0, "ModuleUtils"

    const-string v2, "\u5173\u95ed\u6a21\u5757"

    .line 51
    invoke-static {v0, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 53
    :try_start_0
    new-instance v0, Ljava/io/FileWriter;

    const-string v2, "sys/boptt/dmr_switch"

    invoke-direct {v0, v2}, Ljava/io/FileWriter;-><init>(Ljava/lang/String;)V

    const-string v2, "0"

    .line 54
    invoke-virtual {v0, v2}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V

    .line 55
    invoke-virtual {v0}, Ljava/io/FileWriter;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    return v1

    :catch_0
    const/4 v0, 0x0

    return v0
.end method

.method public static setModuleOn()Z
    .locals 3

    .line 28
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->isModuleOn()Z

    move-result v0

    const/4 v1, 0x1

    if-eqz v0, :cond_0

    return v1

    :cond_0
    const-string v0, "ModuleUtils"

    const-string v2, "\u6253\u5f00\u6a21\u5757"

    .line 31
    invoke-static {v0, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 33
    :try_start_0
    new-instance v0, Ljava/io/FileWriter;

    const-string v2, "sys/boptt/dmr_switch"

    invoke-direct {v0, v2}, Ljava/io/FileWriter;-><init>(Ljava/lang/String;)V

    const-string v2, "1"

    .line 34
    invoke-virtual {v0, v2}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V

    .line 35
    invoke-virtual {v0}, Ljava/io/FileWriter;->close()V
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    return v1

    :catch_0
    const/4 v0, 0x0

    return v0
.end method

.method public static setModuleRestart()Z
    .locals 2

    .line 83
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->setModuleOff()Z

    move-result v0

    if-eqz v0, :cond_0

    const-wide/16 v0, 0x64

    .line 87
    :try_start_0
    invoke-static {v0, v1}, Ljava/lang/Thread;->sleep(J)V

    .line 88
    invoke-static {}, Lcom/bozhou/interphone/util/ModuleUtils;->setModuleOn()Z

    move-result v0
    :try_end_0
    .catch Ljava/lang/InterruptedException; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_0

    :catch_0
    move-exception v0

    .line 90
    invoke-virtual {v0}, Ljava/lang/InterruptedException;->printStackTrace()V

    const/4 v0, 0x0

    :cond_0
    :goto_0
    return v0
.end method
