package handler

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
)

// This marker cannot collide with the Base64 RSA envelope used by older versions.
const senderEnvelopePrefix = "accnotify-sender-v1:"

type senderEnvelope struct {
	Encryption string `json:"encryption"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

func (e senderEnvelope) validate() error {
	if e.Encryption != "aes-256-gcm" {
		return errors.New("加密算法必须为 aes-256-gcm")
	}
	nonce, err := base64.StdEncoding.Strict().DecodeString(e.Nonce)
	if err != nil || len(nonce) != 12 {
		return errors.New("nonce 必须是 12 字节随机值的 Base64 编码")
	}
	ciphertext, err := base64.StdEncoding.Strict().DecodeString(e.Ciphertext)
	if err != nil || len(ciphertext) <= 16 || len(ciphertext) > 4*1024*1024 {
		return errors.New("ciphertext 必须是包含 16 字节认证标签的 Base64 密文，且不超过 4 MB")
	}
	return nil
}

func readSenderEnvelope(payload []byte) (*senderEnvelope, error) {
	if !strings.HasPrefix(string(payload), senderEnvelopePrefix) {
		return nil, nil
	}
	var envelope senderEnvelope
	if err := json.Unmarshal(payload[len(senderEnvelopePrefix):], &envelope); err != nil {
		return nil, err
	}
	if err := envelope.validate(); err != nil {
		return nil, err
	}
	return &envelope, nil
}
