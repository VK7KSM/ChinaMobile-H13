.class public Lcom/bozhou/interphone/util/UnicodeUtils;
.super Ljava/lang/Object;
.source "UnicodeUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 6
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static byteUnicodeToString([B)Ljava/lang/String;
    .locals 7

    .line 23
    new-instance v0, Ljava/lang/StringBuffer;

    invoke-direct {v0}, Ljava/lang/StringBuffer;-><init>()V

    .line 24
    array-length v1, p0

    const/4 v2, 0x2

    rem-int/2addr v1, v2

    if-eqz v1, :cond_0

    .line 25
    new-instance v0, Ljava/lang/String;

    invoke-direct {v0, p0}, Ljava/lang/String;-><init>([B)V

    return-object v0

    :cond_0
    const/4 v1, 0x0

    const/4 v3, 0x0

    .line 27
    :goto_0
    array-length v4, p0

    if-ge v3, v4, :cond_1

    new-array v4, v2, [B

    .line 28
    aget-byte v5, p0, v3

    aput-byte v5, v4, v1

    add-int/lit8 v5, v3, 0x1

    aget-byte v5, p0, v5

    const/4 v6, 0x1

    aput-byte v5, v4, v6

    sget-object v5, Ljava/nio/ByteOrder;->BIG_ENDIAN:Ljava/nio/ByteOrder;

    invoke-static {v4, v5}, Lcom/bozhou/interphone/util/UnicodeUtils;->bytes2Short([BLjava/nio/ByteOrder;)S

    move-result v4

    int-to-char v4, v4

    .line 29
    invoke-virtual {v0, v4}, Ljava/lang/StringBuffer;->append(C)Ljava/lang/StringBuffer;

    add-int/lit8 v3, v3, 0x2

    goto :goto_0

    .line 31
    :cond_1
    invoke-virtual {v0}, Ljava/lang/StringBuffer;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static bytes2Short([BLjava/nio/ByteOrder;)S
    .locals 0

    .line 35
    invoke-static {p0}, Ljava/nio/ByteBuffer;->wrap([B)Ljava/nio/ByteBuffer;

    move-result-object p0

    .line 36
    invoke-virtual {p0, p1}, Ljava/nio/ByteBuffer;->order(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;

    .line 37
    invoke-virtual {p0}, Ljava/nio/ByteBuffer;->getShort()S

    move-result p0

    return p0
.end method

.method public static short2Bytes(SLjava/nio/ByteOrder;)[B
    .locals 1

    const/4 v0, 0x2

    .line 41
    invoke-static {v0}, Ljava/nio/ByteBuffer;->allocate(I)Ljava/nio/ByteBuffer;

    move-result-object v0

    .line 42
    invoke-virtual {v0, p1}, Ljava/nio/ByteBuffer;->order(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;

    .line 43
    invoke-virtual {v0, p0}, Ljava/nio/ByteBuffer;->putShort(S)Ljava/nio/ByteBuffer;

    .line 44
    invoke-virtual {v0}, Ljava/nio/ByteBuffer;->array()[B

    move-result-object p0

    return-object p0
.end method

.method public static stringToByteUnicode(Ljava/lang/String;)[B
    .locals 6

    .line 9
    invoke-virtual {p0}, Ljava/lang/String;->length()I

    move-result v0

    mul-int/lit8 v0, v0, 0x2

    new-array v0, v0, [B

    const/4 v1, 0x0

    const/4 v2, 0x0

    const/4 v3, 0x0

    .line 11
    :goto_0
    invoke-virtual {p0}, Ljava/lang/String;->length()I

    move-result v4

    if-ge v2, v4, :cond_0

    .line 13
    invoke-virtual {p0, v2}, Ljava/lang/String;->charAt(I)C

    move-result v4

    int-to-short v4, v4

    .line 15
    sget-object v5, Ljava/nio/ByteOrder;->BIG_ENDIAN:Ljava/nio/ByteOrder;

    invoke-static {v4, v5}, Lcom/bozhou/interphone/util/UnicodeUtils;->short2Bytes(SLjava/nio/ByteOrder;)[B

    move-result-object v4

    .line 16
    array-length v5, v4

    invoke-static {v4, v1, v0, v3, v5}, Ljava/lang/System;->arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V

    .line 17
    array-length v4, v4

    add-int/2addr v3, v4

    add-int/lit8 v2, v2, 0x1

    goto :goto_0

    :cond_0
    return-object v0
.end method
