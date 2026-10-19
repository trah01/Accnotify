package handler

import (
	"encoding/json"
	"errors"
	"time"

	"github.com/accnotify/server/crypto"
	"github.com/accnotify/server/model"
)

// encryptDeviceMessage encrypts all notification fields with the device public key.
// Missing or invalid keys must never cause a plaintext delivery fallback.
func encryptDeviceMessage(device *model.Device, msg *model.Message) error {
	if envelope, err := readSenderEnvelope(msg.EncryptedPayload); envelope != nil || err != nil {
		return err // Sender ciphertext must never be replaced by server encryption.
	}
	if device.PublicKey == "" {
		return errors.New("missing device public key")
	}
	c := crypto.NewCrypto()
	publicKey, err := c.ParsePublicKey(device.PublicKey)
	if err != nil {
		return err
	}
	fields := map[string]interface{}{
		"title": msg.Title, "body": msg.Body, "group": msg.Group,
		"icon": msg.Icon, "url": msg.URL, "image": msg.Image,
		"sound": msg.Sound, "badge": msg.Badge,
	}
	if msg.NotificationID != "" {
		fields["id"] = msg.NotificationID
	}
	if msg.DeleteRequested {
		fields["delete"] = "1"
	}
	payload, err := json.Marshal(fields)
	if err != nil {
		return err
	}
	var encrypted string
	if device.Platform == "pc" {
		encrypted, err = c.EncryptPCMessage(publicKey, payload)
	} else {
		encrypted, err = c.EncryptMessage(publicKey, payload)
	}
	if err != nil {
		return err
	}
	msg.EncryptedPayload = []byte(encrypted)
	return nil
}

// encryptedWSMessage exposes only routing metadata and ciphertext on the wire.
func encryptedWSMessage(msg *model.Message) *model.WSMessage {
	var data interface{} = map[string]interface{}{"encrypted_content": string(msg.EncryptedPayload)}
	if envelope, err := readSenderEnvelope(msg.EncryptedPayload); err == nil && envelope != nil {
		data = envelope
	}
	timestamp := msg.CreatedAt
	if timestamp.IsZero() {
		timestamp = time.Now()
	}
	return &model.WSMessage{
		Type: model.WSTypeMessage, ID: msg.MessageID, Timestamp: timestamp.Unix(), Sequence: msg.ID,
		Data: data,
	}
}
