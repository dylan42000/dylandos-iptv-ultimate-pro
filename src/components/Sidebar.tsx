// ─── Sidebar Navigation ──────────────────────────────────────────────────────

import React from 'react';
import {
  Home,
  Tv,
  Calendar,
  Film,
  Clapperboard,
  Radio,
  Search,
  Heart,
  Settings,
  User,
  ChevronLeft,
  ChevronRight,
  Download,
  ListPlus,
  LayoutGrid,
} from 'lucide-react';
import { AppPage } from '../types/settings';
import { XtreamProfile } from '../types/xtream';

interface SidebarProps {
  currentPage: AppPage;
  onNavigate: (page: AppPage) => void;
  isCollapsed: boolean;
  onToggleCollapse: () => void;
  activeProfile?: XtreamProfile | null;
  onSwitchProfile: () => void;
}

interface NavItem {
  page: AppPage;
  icon: React.ReactNode;
  label: string;
}

const NAV_ITEMS: NavItem[] = [
  { page: 'home', icon: <Home size={18} />, label: 'Home' },
  { page: 'live', icon: <Tv size={18} />, label: 'Live TV' },
  { page: 'guide', icon: <Calendar size={18} />, label: 'Guide' },
  { page: 'movies', icon: <Film size={18} />, label: 'Movies' },
  { page: 'series', icon: <Clapperboard size={18} />, label: 'Series' },
  { page: 'multiview', icon: <LayoutGrid size={18} />, label: 'Multi-View' },
  { page: 'dvr', icon: <Radio size={18} />, label: 'DVR' },
  { page: 'downloads', icon: <Download size={18} />, label: 'Downloads' },
  { page: 'lists', icon: <ListPlus size={18} />, label: 'Collections' },
  { page: 'search', icon: <Search size={18} />, label: 'Search' },
  { page: 'favorites', icon: <Heart size={18} />, label: 'Favorites' },
];

export const Sidebar: React.FC<SidebarProps> = ({
  currentPage,
  onNavigate,
  isCollapsed,
  onToggleCollapse,
  activeProfile,
  onSwitchProfile,
}) => {
  return (
    <div
      className={`flex flex-col bg-black/30 border-r border-white/5 shrink-0 transition-all duration-200 ${
        isCollapsed ? 'w-16' : 'w-52'
      }`}
    >
      {/* Collapse Toggle */}
      <button
        onClick={onToggleCollapse}
        className="flex items-center justify-center h-10 mx-2 mt-2 
          text-white/30 hover:text-white/60 hover:bg-white/5 rounded-lg transition-all"
      >
        {isCollapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
      </button>

      {/* Navigation Items */}
      <nav className="flex-1 flex flex-col gap-1 px-2 mt-2">
        {NAV_ITEMS.map((item) => {
          const isActive = currentPage === item.page;
          return (
            <button
              key={item.page}
              onClick={() => onNavigate(item.page)}
              className={`
                flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all group
                ${
                  isActive
                    ? 'theme-accent-surface theme-text-accent'
                    : 'text-white/50 hover:text-white hover:bg-white/5'
                }
              `}
              title={isCollapsed ? item.label : undefined}
            >
              <span className="shrink-0">{item.icon}</span>
              {!isCollapsed && (
                <span className="text-sm font-medium truncate">
                  {item.label}
                </span>
              )}
              {isActive && !isCollapsed && (
                <div className="ml-auto w-1.5 h-1.5 rounded-full theme-accent-bg" />
              )}
            </button>
          );
        })}
      </nav>

      {/* Profile Switcher */}
      <div className="px-2 mb-2">
        <button
          onClick={onSwitchProfile}
          className="flex items-center gap-3 w-full px-3 py-2.5 text-white/50 
            hover:text-white hover:bg-white/5 rounded-xl transition-all group"
          title={isCollapsed ? 'Switch Profile' : undefined}
        >
          <div className="w-7 h-7 rounded-full theme-accent-surface flex items-center justify-center shrink-0">
            <User size={14} className="theme-text-accent" />
          </div>
          {!isCollapsed && (
            <div className="flex-1 min-w-0 text-left">
              <p className="text-xs font-medium text-white/80 truncate">
                {activeProfile?.name || activeProfile?.username || 'No Profile'}
              </p>
              <p className="text-[10px] text-white/30">Switch Profile</p>
            </div>
          )}
        </button>

        {/* Settings */}
        <button
          onClick={() => onNavigate('settings')}
          className={`
            flex items-center gap-3 w-full px-3 py-2.5 rounded-xl transition-all mt-1
            ${
              currentPage === 'settings'
                ? 'theme-accent-surface theme-text-accent'
                : 'text-white/50 hover:text-white hover:bg-white/5'
            }
          `}
          title={isCollapsed ? 'Settings' : undefined}
        >
          <Settings size={18} className="shrink-0" />
          {!isCollapsed && (
            <span className="text-sm font-medium">Settings</span>
          )}
        </button>
      </div>
    </div>
  );
};
