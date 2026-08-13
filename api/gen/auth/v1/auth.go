package authv1

type RegisterRequest struct {
	DeviceId  string
	PublicKey string
}

type RegisterResponse struct {
	AccessToken  string
	RefreshToken string
	AccountId    string
	ExpiresAt    int64
}

type RefreshTokenRequest struct {
	RefreshToken string
}

type RefreshTokenResponse struct {
	AccessToken  string
	RefreshToken string
	ExpiresAt    int64
}

type ValidateTokenRequest struct {
	AccessToken string
}

type ValidateTokenResponse struct {
	Valid     bool
	AccountId string
	Tier      string
}

type GetAccountRequest struct {
	AccountId string
}

type GetAccountResponse struct {
	AccountId         string
	Tier              string
	SubscriptionExpiry int64
	Status            string
	CreatedAt         int64
}

type DeleteAccountRequest struct {
	AccountId string
}

type DeleteAccountResponse struct {
	Success bool
}
