package model

import (
	"encoding/json"
)

// NotificationID follows Bark: non-string JSON identifiers are ignored.
type NotificationID string

func (id *NotificationID) UnmarshalJSON(data []byte) error {
	var value string
	if json.Unmarshal(data, &value) == nil {
		*id = NotificationID(value)
	} else {
		*id = ""
	}
	return nil
}

type PushFlag string

func (flag *PushFlag) UnmarshalJSON(data []byte) error {
	var value string
	if json.Unmarshal(data, &value) == nil {
		*flag = PushFlag(value)
	} else if string(data) == "1" || string(data) == "true" {
		*flag = "1"
	} else {
		*flag = ""
	}
	return nil
}

func (flag PushFlag) Enabled() bool { return flag == "1" }
