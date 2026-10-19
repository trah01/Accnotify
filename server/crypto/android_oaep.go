package crypto

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"io"
	"math/big"
)

// encryptAndroidOAEP implements RFC 8017 section 7.1.1 with SHA-256 for
// OAEP and SHA-1 for MGF1, as required by Android Keystore on older devices.
// rsa.EncryptOAEP uses the same hash for both and cannot express this pair.
// Only the public RSA operation is performed here; no private key is handled.
func encryptAndroidOAEP(key *rsa.PublicKey, message []byte) ([]byte, error) {
	if key == nil || key.N == nil || key.N.Sign() <= 0 || key.E < 3 || key.E%2 == 0 {
		return nil, errors.New("invalid RSA public key")
	}
	size := key.Size()
	if len(message) > size-2*sha256.Size-2 {
		return nil, rsa.ErrMessageTooLong
	}
	// EM = 0x00 || maskedSeed || maskedDB; DB = lHash || PS || 0x01 || M.
	encoded := make([]byte, size)
	seed := encoded[1 : 1+sha256.Size]
	db := encoded[1+sha256.Size:]
	labelHash := sha256.Sum256(nil)
	copy(db, labelHash[:])
	db[len(db)-len(message)-1] = 1
	copy(db[len(db)-len(message):], message)
	if _, err := io.ReadFull(rand.Reader, seed); err != nil {
		return nil, err
	}
	xorMGF1SHA1(db, seed)
	xorMGF1SHA1(seed, db)
	// EM starts with zero and is therefore smaller than the modulus.
	value := new(big.Int).SetBytes(encoded)
	value.Exp(value, big.NewInt(int64(key.E)), key.N)
	return value.FillBytes(make([]byte, size)), nil
}

func xorMGF1SHA1(destination, seed []byte) {
	var counter [4]byte
	for offset, i := 0, uint32(0); offset < len(destination); i++ {
		h := sha1.New()
		h.Write(seed)
		binary.BigEndian.PutUint32(counter[:], i)
		h.Write(counter[:])
		for _, value := range h.Sum(nil) {
			if offset == len(destination) {
				break
			}
			destination[offset] ^= value
			offset++
		}
	}
}
