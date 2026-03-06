import "./App.css"
import { useState, useEffect } from "react";
import { keycloak } from "./main"
import authFetch from "./authFetch"

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

  const [data, setData] = useState<ListFilesView | null>(null)
  const [filename, setFilename] = useState("Click to choose a file");
  const [file, setFile] = useState<File | null>(null);
  const [selectedFiles, setSelectedFiles] = useState<string[]>([]);

  const username = keycloak.tokenParsed?.preferred_username;


  const loadData = async () => {
    const res = await authFetch("/")
    setData(await res.json())
  }

  const upload = async () => {
    if (!file) return;
    const formData = new FormData();
    formData.append("file", file)
    await authFetch("/files", {
      method: "POST",
      body: formData,
    })
    setFile(null)
    setFilename("Click to choose a file")
    loadData()
  }

  const deleteSelected = async () => {
    await authFetch("/files/delete", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(selectedFiles)
    });
    setSelectedFiles([]);
    loadData()
  }

  const toggleFile = (name: string) =>
    setSelectedFiles(
      prev => prev.includes(name) ?
        prev.filter(f => f !== name) : [...prev, name]
    )

  useEffect(() => { loadData() }, [])



  return (
    <>
      <div className="glass navbar">
        <div className="navbar__left">
          <span className="navbar__brand">FileVault</span>
          {keycloak.hasRealmRole("admin") &&
            (<a className="navbar__admin-badge" href="/admin">Admin</a>)
          }
        </div>
        <div className="navbar__right">
          <span className="navbar__username">{username}</span>
          <button className="navbar__logout"
            onClick={() => keycloak.logout(
              { redirectUri: "http://localhost:5173/" })}>Logout</button>
        </div>
      </div>

      <div className="main">

        <div className="glass upload-card">
          <div className="upload-card__header">
            <h2 className="upload-card__title">Upload a file</h2>
            <span className="upload-card__size">totalSize / maxsize</span>
          </div>
          <div className="upload-card__body">
            <label className="upload-card__dropzone">
              <UploadIcon />
              <span>{filename}</span>
              <input type="file"
                hidden
                onChange={e => {
                  setFile(e.target.files?.[0] ?? null);
                  setFilename((e.target.files?.[0]?.name ?? "Click to choose a file"));
                }}
              />
            </label>
            <button className="upload-card__submit"
              onClick={upload}>Upload
            </button>
          </div>
        </div>

        <div className="glass listfiles-card">
          <h2 className="listfiles-card__title">Your files</h2>

          {data && data.files.length > 0 && (
            <div>
              <ul className="listfiles-card__list">
                {data.files.map(name => (
                  <li className="listfiles-card__row" key={name}>
                    <input
                      className="listfiles-card__checkbox"
                      type="checkbox"
                      checked={selectedFiles.includes(name)}
                      onChange={() => toggleFile(name)}
                    />
                    <a className="listfiles-card__filename"
                      href={`/files/${name}`}>{name}</a>
                  </li>
                ))}
              </ul>
              <button
                className="listfiles-card__delete-btn"
                onClick={deleteSelected} disabled={selectedFiles.length === 0}>
                Delete selected
              </button>
            </div>
          )}
        </div>



      </div >
    </>


  )
}


export default App
