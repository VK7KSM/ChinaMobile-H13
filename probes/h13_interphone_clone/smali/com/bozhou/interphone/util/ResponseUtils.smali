.class public Lcom/bozhou/interphone/util/ResponseUtils;
.super Ljava/lang/Object;
.source "ResponseUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 6
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static isRecvCsbk(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 95
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOAUTOUPDATACSBKINFO"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvMessage(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 50
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOAUTOUPDATAMESGINFO"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvModuleState(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 41
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOSENDMOUDULESTA"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvPlayState(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 23
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOSENDMOUDULEPLAYSTA"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvRxInfo(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 59
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOAUTOUPDATADIGITALRXINFO"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvSetAnalogCh(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 86
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOSETANALOGCH"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvSetCsbk(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 68
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOSETCSBK"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvSetDigitalCh(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 77
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOSETDIGITALCH"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvStartUp(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 32
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOSTARTUP"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvVersion(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 104
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOGETSOFTVERSION"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method

.method public static isRecvVoiceInfo(Lcom/cttdy/splibrary/Response;)Z
    .locals 1

    if-eqz p0, :cond_0

    .line 14
    invoke-virtual {p0}, Lcom/cttdy/splibrary/Response;->command()Lokio/ByteString;

    move-result-object p0

    invoke-virtual {p0}, Lokio/ByteString;->utf8()Ljava/lang/String;

    move-result-object p0

    const-string v0, "DMOAUTOUPDATARECORDINFO"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method
