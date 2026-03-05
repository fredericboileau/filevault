import './App.css'
import { useState } from 'react';
import { keycloak } from './main'

const UploadIcon = () => (
  <svg className="upload-card__icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
      d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
  </svg>
)

function App() {

  type ListFilesView = {
    files: string[]
    username: string
    totalSize: number
    maxSize: number
    otherUsers: Record<string, string>
    shares: Record<string, string[]>
  }

  const username = keycloak.tokenParsed?.preferred_username;
  const [filename, setFilename] = useState('Click to choose a file');
  const [file, setFile] = useState<File | null>(null);

  const upload = async () => {
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file)
    await fetch('/upload', { method: 'POST', body: formData })
  }

  return (
    <>
      {/* // navbar */}
      <div className="glass navbar">
        <div className="navbar__left">
          <span className="navbar__brand">FileVault</span>
          {keycloak.hasRealmRole('admin') &&
            (<a className="navbar__admin-badge" href="/admin">Admin</a>)
          }
        </div>
        <div className="navbar__right">
          <span className="navbar__username">{username}</span>
          <button className="navbar__logout"
            onClick={() => keycloak.logout(
              { redirectUri: 'http://localhost:5173/' })}>Logout</button>
        </div>
      </div>

      <div className="main">

        <div className="glass upload-card">
          <div className='upload-card__header'>
            <h2>Upload a file</h2>
            <span className='upload-card__size'>totalSize / maxsize</span>
          </div>
          <div className='upload-card__body'>
            <label className='upload-card__dropzone'>
              <UploadIcon />
              <span>{filename}</span>
              <input type='file'
                hidden
                onChange={e => {
                  setFile(e.target.files?.[0] ?? null);
                  setFilename((e.target.files?.[0]?.name ?? 'Click to choose a file'));
                }}
              />
            </label>
            <button className='upload-card__submit'
              onClick={upload}>Upload
            </button>
          </div>
        </div>
      </div>
    </>


  )
}


export default App
