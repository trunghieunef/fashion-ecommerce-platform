// TASK:WEB-02a: typed auth/user/address calls (contracts/openapi/user.yaml), all same-origin through the Gateway.
import { request } from '../http';
import { authRequest } from './session';

export type Locale = 'vi' | 'en';
export type User = { id: string; email: string; full_name: string; locale: Locale };
export type Session = { user?: User; access_token: string; token_type: 'Bearer'; expires_in: number };
export type Profile = User & { email_verified: boolean };
export type AddressInput = {
  recipient_name: string;
  phone: string;
  province_code: string;
  district_code?: string | null;
  ward_code: string;
  address_line: string;
  is_default?: boolean;
};
export type Address = Required<Omit<AddressInput, 'district_code'>> & { id: string; district_code: string | null };
export type RegisterInput = { email: string; password: string; full_name: string; locale: Locale };

const AUTH = '/api/v1/auth';
const ME = '/api/v1/users/me';

export const login = (email: string, password: string) =>
  request<Session>(`${AUTH}/login`, { method: 'POST', body: { email, password } });
export const register = (input: RegisterInput) => request<Session>(`${AUTH}/register`, { method: 'POST', body: input });
export const logout = () => request<void>(`${AUTH}/logout`, { method: 'POST' });
export const forgotPassword = (email: string) => request<void>(`${AUTH}/password/forgot`, { method: 'POST', body: { email } });
export const resetPassword = (token: string, newPassword: string) =>
  request<void>(`${AUTH}/password/reset`, { method: 'POST', body: { token, new_password: newPassword } });
export const changePassword = (currentPassword: string, newPassword: string) =>
  authRequest<void>(`${AUTH}/password/change`, { method: 'POST', body: { current_password: currentPassword, new_password: newPassword } });

export const getMe = () => authRequest<Profile>(ME);
export const updateMe = (input: { full_name: string; locale: Locale }) => authRequest<Profile>(ME, { method: 'PUT', body: input });

export const listAddresses = () => authRequest<Address[]>(`${ME}/addresses`);
export const createAddress = (input: AddressInput) => authRequest<Address>(`${ME}/addresses`, { method: 'POST', body: input });
export const updateAddress = (id: string, input: AddressInput) =>
  authRequest<Address>(`${ME}/addresses/${encodeURIComponent(id)}`, { method: 'PUT', body: input });
export const deleteAddress = (id: string) => authRequest<void>(`${ME}/addresses/${encodeURIComponent(id)}`, { method: 'DELETE' });
