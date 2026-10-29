import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { RequireAuth, RequireCustomer, RequirePermission } from './components/RouteGuards';
import { AdminGamesPage } from './pages/AdminGamesPage';
import { AdminListingsPage } from './pages/AdminListingsPage';
import { AdminSettingsPage } from './pages/AdminSettingsPage';
import { AdminUsersPage } from './pages/AdminUsersPage';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { FinancePage } from './pages/FinancePage';
import { HelpPage } from './pages/HelpPage';
import { HomePage } from './pages/HomePage';
import { ListingDetailPage } from './pages/ListingDetailPage';
import { ListingsPage } from './pages/ListingsPage';
import { LoginPage } from './pages/LoginPage';
import { MyListingsPage } from './pages/MyListingsPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { OrdersPage } from './pages/OrdersPage';
import { ProfilePage } from './pages/ProfilePage';
import { RegisterPage } from './pages/RegisterPage';
import { RolesPage } from './pages/RolesPage';
import { SupportDisputesPage } from './pages/SupportDisputesPage';
import { TicketsPage } from './pages/TicketsPage';
import { WalletPage } from './pages/WalletPage';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="listings" element={<ListingsPage />} />
        <Route path="listings/:id" element={<ListingDetailPage />} />
        <Route path="help" element={<HelpPage />} />

        <Route
          path="orders"
          element={
            <RequireAuth>
              <RequireCustomer>
                <OrdersPage />
              </RequireCustomer>
            </RequireAuth>
          }
        />
        <Route
          path="orders/:id"
          element={
            <RequireAuth>
              <OrderDetailPage />
            </RequireAuth>
          }
        />
        <Route
          path="my-listings"
          element={
            <RequireAuth>
              <RequireCustomer>
                <MyListingsPage />
              </RequireCustomer>
            </RequireAuth>
          }
        />
        <Route
          path="wallet"
          element={
            <RequireAuth>
              <RequireCustomer>
                <WalletPage />
              </RequireCustomer>
            </RequireAuth>
          }
        />
        <Route
          path="tickets"
          element={
            <RequireAuth>
              <TicketsPage />
            </RequireAuth>
          }
        />
        <Route
          path="tickets/:id"
          element={
            <RequireAuth>
              <TicketsPage />
            </RequireAuth>
          }
        />
        <Route
          path="notifications"
          element={
            <RequireAuth>
              <NotificationsPage />
            </RequireAuth>
          }
        />
        <Route
          path="admin/listings"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['listing:review_queue']}>
                <AdminListingsPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="profile"
          element={
            <RequireAuth>
              <ProfilePage />
            </RequireAuth>
          }
        />
        <Route
          path="support/disputes"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['dispute:read_any']}>
                <SupportDisputesPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="admin/games"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['game:read_hidden']}>
                <AdminGamesPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="admin/users"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['user:read']}>
                <AdminUsersPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="admin/approvals"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['approval:list', 'game:change_tos']}>
                <ApprovalsPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="finance"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['withdrawal:list_all', 'ledger:read']}>
                <FinancePage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="admin/roles"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['role:read']}>
                <RolesPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route
          path="admin/settings"
          element={
            <RequireAuth>
              <RequirePermission anyOf={['setting:read']}>
                <AdminSettingsPage />
              </RequirePermission>
            </RequireAuth>
          }
        />
        <Route path="*" element={<p className="muted">Không tìm thấy trang.</p>} />
      </Route>
    </Routes>
  );
}
